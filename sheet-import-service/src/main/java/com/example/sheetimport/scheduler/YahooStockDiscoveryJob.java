package com.example.sheetimport.scheduler;

import com.example.sheetimport.model.StockAnalytics;
import com.example.sheetimport.repository.StockAnalyticsRepository;
import com.example.sheetimport.service.YahooFinanceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Scheduled job to discover new NSE stocks from Yahoo Finance.
 * Runs every 2-3 days to find newly listed stocks and add them to the database.
 * New stocks are marked with source="YAHOO" and will be polled for price updates.
 */
@Component
public class YahooStockDiscoveryJob {

    private static final Logger log = LoggerFactory.getLogger(YahooStockDiscoveryJob.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String SOURCE_YAHOO = "YAHOO";

    private final StockAnalyticsRepository repository;
    private final YahooFinanceService yahooFinanceService;

    @Value("${yahoo.discovery.enabled:true}")
    private boolean discoveryEnabled;

    public YahooStockDiscoveryJob(StockAnalyticsRepository repository, YahooFinanceService yahooFinanceService) {
        this.repository = repository;
        this.yahooFinanceService = yahooFinanceService;
    }

    /**
     * Discover new NSE stocks every 2 days at 6 AM IST.
     * Uses Yahoo Finance screener API to get list of all NSE stocks.
     */
    @Scheduled(cron = "${yahoo.discovery.cron:0 0 6 */2 * *}", zone = "Asia/Kolkata")
    public void discoverNewStocks() {
        if (!discoveryEnabled) {
            log.debug("Yahoo stock discovery is disabled");
            return;
        }

        log.info("Starting Yahoo Finance stock discovery job");
        try {
            int discovered = discoverFromYahooScreener();
            log.info("Stock discovery completed. Found {} new stocks", discovered);
        } catch (Exception e) {
            log.error("Error during stock discovery: {}", e.getMessage(), e);
        }
    }

    /**
     * Use Yahoo Finance screener API to get all NSE-listed stocks.
     * Compares with existing database and adds new ones.
     */
    public int discoverFromYahooScreener() {
        Set<String> existingTickers = new HashSet<>();
        repository.findAll().forEach(s -> {
            existingTickers.add(s.getTicker().toUpperCase());
            // Also add without NSE: prefix for comparison
            if (s.getTicker().startsWith("NSE:")) {
                existingTickers.add(s.getTicker().substring(4).toUpperCase());
            }
        });

        log.info("Existing stocks in DB: {}", existingTickers.size());

        int newStocksAdded = 0;
        int offset = 0;
        int batchSize = 100;
        boolean hasMore = true;

        while (hasMore) {
            try {
                Set<String> batchTickers = fetchYahooScreenerBatch(offset, batchSize);

                if (batchTickers.isEmpty()) {
                    hasMore = false;
                    continue;
                }

                for (String yahooSymbol : batchTickers) {
                    // Convert Yahoo symbol (TCS.NS) to our format (NSE:TCS)
                    String ticker = convertYahooToNseTicker(yahooSymbol);
                    String pureTicker = ticker.startsWith("NSE:") ? ticker.substring(4) : ticker;

                    // Check if already exists
                    if (existingTickers.contains(ticker.toUpperCase()) ||
                        existingTickers.contains(pureTicker.toUpperCase())) {
                        continue;
                    }

                    // Create new stock entry
                    StockAnalytics newStock = new StockAnalytics();
                    newStock.setTicker(ticker);
                    newStock.setSource(SOURCE_YAHOO);
                    newStock.setLastUpdated(LocalDateTime.now());

                    // Fetch initial data from Yahoo Finance
                    fetchInitialData(newStock, yahooSymbol);

                    repository.save(newStock);
                    existingTickers.add(ticker.toUpperCase());
                    newStocksAdded++;

                    log.info("Discovered new stock: {}", ticker);
                }

                offset += batchSize;

                // Small delay between batches to avoid rate limiting
                Thread.sleep(1000);

                // Safety limit - don't fetch more than 5000 stocks
                if (offset > 5000) {
                    hasMore = false;
                }

            } catch (Exception e) {
                log.error("Error fetching batch at offset {}: {}", offset, e.getMessage());
                hasMore = false;
            }
        }

        return newStocksAdded;
    }

    /**
     * Fetch a batch of NSE stocks from Yahoo Finance screener.
     */
    private Set<String> fetchYahooScreenerBatch(int offset, int size) throws Exception {
        Set<String> tickers = new HashSet<>();

        // Yahoo Finance screener endpoint for Indian stocks
        String screenerUrl = "https://query2.finance.yahoo.com/v1/finance/screener/predefined/saved?" +
                "formatted=false&lang=en-US&region=IN&scrIds=most_actives_in&count=" + size + "&start=" + offset;

        URL url = new URL(screenerUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setRequestProperty("Accept", "application/json");

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            log.warn("Yahoo screener returned HTTP {}", responseCode);
            return tickers;
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }

        JsonNode root = objectMapper.readTree(response.toString());
        JsonNode quotes = root.path("finance").path("result").get(0).path("quotes");

        if (quotes != null && quotes.isArray()) {
            for (JsonNode quote : quotes) {
                String symbol = quote.path("symbol").asText();
                // Only process .NS (NSE) stocks
                if (symbol != null && symbol.endsWith(".NS")) {
                    tickers.add(symbol);
                }
            }
        }

        log.debug("Fetched {} NSE stocks from Yahoo screener (offset={})", tickers.size(), offset);
        return tickers;
    }

    /**
     * Convert Yahoo Finance symbol to NSE ticker format.
     * TCS.NS -> NSE:TCS
     */
    private String convertYahooToNseTicker(String yahooSymbol) {
        if (yahooSymbol == null) return null;
        String ticker = yahooSymbol.toUpperCase();
        if (ticker.endsWith(".NS")) {
            ticker = ticker.substring(0, ticker.length() - 3);
        } else if (ticker.endsWith(".BO")) {
            ticker = ticker.substring(0, ticker.length() - 3);
        }
        return "NSE:" + ticker;
    }

    /**
     * Fetch initial data for a newly discovered stock.
     * Fetches price data from chart API and additional data from quote API.
     */
    private void fetchInitialData(StockAnalytics stock, String yahooSymbol) {
        // First fetch price data from chart API (1 year range for cmp365)
        fetchChartData(stock, yahooSymbol);

        // Then fetch additional data (market cap, sector, industry) from quote API
        fetchQuoteData(stock, yahooSymbol);
    }

    /**
     * Fetch chart data including price history for rank calculations.
     */
    private void fetchChartData(StockAnalytics stock, String yahooSymbol) {
        try {
            // Use 1 year range to get historical data for cmp365 and rank calculations
            String apiUrl = "https://query1.finance.yahoo.com/v8/finance/chart/" + yahooSymbol + "?interval=1d&range=1y";

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return;
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            JsonNode root = objectMapper.readTree(response.toString());
            JsonNode resultArray = root.path("chart").path("result");
            if (!resultArray.isArray() || resultArray.size() == 0) return;

            JsonNode result = resultArray.get(0);
            JsonNode meta = result.path("meta");
            JsonNode closes = result.path("indicators").path("quote").get(0).path("close");

            // Get current price
            double currentPrice = 0;
            if (meta.has("regularMarketPrice")) {
                currentPrice = meta.get("regularMarketPrice").asDouble();
                stock.setCmp(currentPrice);
            }

            // Get 52-week high/low
            if (meta.has("fiftyTwoWeekHigh")) {
                stock.setHigh52Week(meta.get("fiftyTwoWeekHigh").asDouble());
            }
            if (meta.has("fiftyTwoWeekLow")) {
                stock.setLow52Week(meta.get("fiftyTwoWeekLow").asDouble());
            }

            // Get company name
            if (meta.has("shortName")) {
                stock.setName(meta.get("shortName").asText());
            } else if (meta.has("longName")) {
                stock.setName(meta.get("longName").asText());
            }

            // Calculate daily change using previousClose (yesterday's close)
            // NOT chartPreviousClose which is the close before the range starts (1 year ago!)
            if (meta.has("regularMarketPrice") && meta.has("previousClose")) {
                double prevClose = meta.get("previousClose").asDouble();
                if (prevClose > 0) {
                    double dailyChange = ((currentPrice - prevClose) / prevClose) * 100;
                    stock.setDailyChange(Math.round(dailyChange * 100.0) / 100.0);
                }
            } else if (closes != null && closes.isArray() && closes.size() >= 2 && currentPrice > 0) {
                // Fallback: calculate from closes array (second-to-last value)
                Double yesterdayClose = getValidPrice(closes, closes.size() - 2, 1);
                if (yesterdayClose != null && yesterdayClose > 0) {
                    double dailyChange = ((currentPrice - yesterdayClose) / yesterdayClose) * 100;
                    stock.setDailyChange(Math.round(dailyChange * 100.0) / 100.0);
                }
            }

            // Calculate historical returns from closes array
            if (closes != null && closes.isArray() && closes.size() > 0 && currentPrice > 0) {
                int size = closes.size();

                // cmp365 - price 1 year ago (first data point)
                Double price1YAgo = getValidPrice(closes, 0, 5);
                if (price1YAgo != null) {
                    stock.setCmp365(price1YAgo);
                    // rank1Year = % change over 1 year
                    double rank1Year = ((currentPrice - price1YAgo) / price1YAgo) * 100;
                    stock.setRank1Year(Math.round(rank1Year * 100.0) / 100.0);
                }

                // rank1Week - % change over ~5 trading days
                Double price1WAgo = getValidPrice(closes, Math.max(0, size - 6), 3);
                if (price1WAgo != null && price1WAgo > 0) {
                    double rank1Week = ((currentPrice - price1WAgo) / price1WAgo) * 100;
                    stock.setRank1Week(Math.round(rank1Week * 100.0) / 100.0);
                }

                // rank1Month - % change over ~21 trading days
                Double price1MAgo = getValidPrice(closes, Math.max(0, size - 22), 5);
                if (price1MAgo != null && price1MAgo > 0) {
                    double rank1Month = ((currentPrice - price1MAgo) / price1MAgo) * 100;
                    stock.setRank1Month(Math.round(rank1Month * 100.0) / 100.0);
                }

                // rank2Month - % change over ~42 trading days
                Double price2MAgo = getValidPrice(closes, Math.max(0, size - 43), 5);
                if (price2MAgo != null && price2MAgo > 0) {
                    double rank2Month = ((currentPrice - price2MAgo) / price2MAgo) * 100;
                    stock.setRank2Month(Math.round(rank2Month * 100.0) / 100.0);
                }
            }

            stock.setHigh52WeekUpdated(LocalDateTime.now());

        } catch (Exception e) {
            log.debug("Could not fetch chart data for {}: {}", yahooSymbol, e.getMessage());
        }
    }

    /**
     * Get a valid (non-null) price from closes array near the target index.
     */
    private Double getValidPrice(JsonNode closes, int targetIndex, int searchRange) {
        for (int i = targetIndex; i < targetIndex + searchRange && i < closes.size(); i++) {
            if (!closes.get(i).isNull()) {
                return closes.get(i).asDouble();
            }
        }
        return null;
    }

    /**
     * Fetch additional data (market cap, sector, industry) from Yahoo Finance quote API.
     */
    private void fetchQuoteData(StockAnalytics stock, String yahooSymbol) {
        try {
            // Use v6 quote API for market cap and other fundamentals
            String apiUrl = "https://query1.finance.yahoo.com/v6/finance/quote?symbols=" + yahooSymbol;

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                // Try NSE API as fallback for sector/industry
                fetchSectorFromNSE(stock, yahooSymbol);
                return;
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            JsonNode root = objectMapper.readTree(response.toString());
            JsonNode quotes = root.path("quoteResponse").path("result");

            if (quotes.isArray() && quotes.size() > 0) {
                JsonNode quote = quotes.get(0);

                // Market cap (in raw value, convert to crores)
                if (quote.has("marketCap")) {
                    double marketCapRaw = quote.get("marketCap").asDouble();
                    // Convert to crores (1 crore = 10 million = 10,000,000)
                    double marketCapCrores = marketCapRaw / 10000000;
                    stock.setMarketCap(Math.round(marketCapCrores * 100.0) / 100.0);
                }

                // Sector from Yahoo (if available)
                if (quote.has("sector") && !quote.get("sector").isNull()) {
                    stock.setSector(quote.get("sector").asText());
                }

                // Industry from Yahoo (if available)
                if (quote.has("industry") && !quote.get("industry").isNull()) {
                    stock.setIndustry(quote.get("industry").asText());
                }

                // If no sector/industry from Yahoo, try NSE
                if (stock.getSector() == null || stock.getIndustry() == null) {
                    fetchSectorFromNSE(stock, yahooSymbol);
                }

                if (stock.getSector() != null || stock.getIndustry() != null) {
                    stock.setSectorUpdated(LocalDateTime.now());
                }
            }

        } catch (Exception e) {
            log.debug("Could not fetch quote data for {}: {}", yahooSymbol, e.getMessage());
            // Try NSE API as fallback
            fetchSectorFromNSE(stock, yahooSymbol);
        }
    }

    /**
     * Fetch sector/industry from NSE India API as fallback.
     */
    private void fetchSectorFromNSE(StockAnalytics stock, String yahooSymbol) {
        try {
            // Extract pure ticker (remove .NS)
            String nseSymbol = yahooSymbol.replace(".NS", "").replace(".BO", "");

            String apiUrl = "https://www.nseindia.com/api/quote-equity?symbol=" + nseSymbol;

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return;
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            JsonNode root = objectMapper.readTree(response.toString());

            // Get industry from info section
            if (stock.getIndustry() == null && root.has("info") && root.path("info").has("industry")) {
                stock.setIndustry(root.path("info").get("industry").asText());
            }

            // Get sector from metadata
            if (stock.getSector() == null && root.has("metadata") && root.path("metadata").has("pdSectorInd")) {
                String sectorIndex = root.path("metadata").get("pdSectorInd").asText();
                stock.setSector(mapSectorIndex(sectorIndex));
            }

            if (stock.getSector() != null || stock.getIndustry() != null) {
                stock.setSectorUpdated(LocalDateTime.now());
            }

        } catch (Exception e) {
            // Silent fail - sector data is optional
        }
    }

    /**
     * Map NSE sector index to standard sector name.
     */
    private String mapSectorIndex(String sectorIndex) {
        if (sectorIndex == null) return null;
        String upper = sectorIndex.toUpperCase();

        if (upper.contains("IT") || upper.contains("DIGITAL")) return "Technology";
        if (upper.contains("BANK") || upper.contains("FINANCIAL")) return "Financial Services";
        if (upper.contains("PHARMA") || upper.contains("HEALTHCARE")) return "Healthcare";
        if (upper.contains("AUTO")) return "Automobile";
        if (upper.contains("METAL") || upper.contains("STEEL")) return "Metals & Mining";
        if (upper.contains("ENERGY") || upper.contains("OIL") || upper.contains("GAS")) return "Energy";
        if (upper.contains("FMCG") || upper.contains("CONSUMER")) return "Consumer Goods";
        if (upper.contains("REALTY") || upper.contains("INFRA")) return "Real Estate";
        if (upper.contains("TELECOM") || upper.contains("MEDIA")) return "Telecom & Media";

        return sectorIndex; // Return as-is if no mapping
    }

    /**
     * Manual trigger for stock discovery (for testing/catch-up).
     */
    public int triggerDiscovery() {
        log.info("Manual stock discovery triggered");
        return discoverFromYahooScreener();
    }
}
