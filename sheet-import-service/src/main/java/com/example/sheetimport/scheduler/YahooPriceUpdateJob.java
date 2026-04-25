package com.example.sheetimport.scheduler;

import com.example.sheetimport.model.StockAnalytics;
import com.example.sheetimport.repository.StockAnalyticsRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Scheduled job to update prices for Yahoo-sourced stocks.
 * Runs every 5 minutes during market hours to keep prices current.
 * Only updates stocks with source="YAHOO".
 */
@Component
public class YahooPriceUpdateJob {

    private static final Logger log = LoggerFactory.getLogger(YahooPriceUpdateJob.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String SOURCE_YAHOO = "YAHOO";

    private final StockAnalyticsRepository repository;

    @Value("${yahoo.price.update.enabled:true}")
    private boolean priceUpdateEnabled;

    public YahooPriceUpdateJob(StockAnalyticsRepository repository) {
        this.repository = repository;
    }

    /**
     * Update prices every 5 minutes during market hours (9:15 AM - 3:30 PM IST).
     * Only updates stocks with source="YAHOO".
     */
    @Scheduled(cron = "${yahoo.price.update.cron:0 */5 9-15 * * MON-FRI}", zone = "Asia/Kolkata")
    public void updatePrices() {
        if (!priceUpdateEnabled) {
            log.debug("Yahoo price update is disabled");
            return;
        }

        log.info("Starting Yahoo price update for YAHOO-sourced stocks");
        try {
            int updated = updateYahooStockPrices();
            log.info("Yahoo price update completed. Updated {} stocks", updated);
        } catch (Exception e) {
            log.error("Error during Yahoo price update: {}", e.getMessage(), e);
        }
    }

    /**
     * Update prices for all YAHOO-sourced stocks using parallel processing.
     */
    public int updateYahooStockPrices() {
        List<StockAnalytics> yahooStocks = repository.findBySource(SOURCE_YAHOO);

        if (yahooStocks.isEmpty()) {
            log.debug("No YAHOO-sourced stocks to update");
            return 0;
        }

        log.info("Updating prices for {} YAHOO-sourced stocks", yahooStocks.size());

        int parallelThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(parallelThreads);

        List<CompletableFuture<Boolean>> futures = new ArrayList<>();

        for (StockAnalytics stock : yahooStocks) {
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                return updateStockPrice(stock);
            }, executor);
            futures.add(future);
        }

        int totalSuccess = 0;
        for (CompletableFuture<Boolean> future : futures) {
            try {
                if (future.get(30, TimeUnit.SECONDS)) {
                    totalSuccess++;
                }
            } catch (Exception e) {
                // Continue with next
            }
        }

        executor.shutdown();
        return totalSuccess;
    }

    /**
     * Update price for a single stock from Yahoo Finance.
     * Fetches 1 year of data to calculate all rank fields.
     */
    private boolean updateStockPrice(StockAnalytics stock) {
        String yahooSymbol = toYahooSymbol(stock.getTicker());
        if (yahooSymbol == null) return false;

        try {
            // Use 1 year range to get historical data for rank calculations
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
                return false;
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
            if (!resultArray.isArray() || resultArray.size() == 0) return false;

            JsonNode result = resultArray.get(0);
            JsonNode meta = result.path("meta");
            JsonNode closes = result.path("indicators").path("quote").get(0).path("close");

            boolean updated = false;

            // Get current price
            double currentPrice = 0;
            if (meta.has("regularMarketPrice")) {
                currentPrice = meta.get("regularMarketPrice").asDouble();
                stock.setCmp(currentPrice);
                updated = true;
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

            // Update 52-week data if available
            if (meta.has("fiftyTwoWeekHigh")) {
                stock.setHigh52Week(meta.get("fiftyTwoWeekHigh").asDouble());
            }
            if (meta.has("fiftyTwoWeekLow")) {
                stock.setLow52Week(meta.get("fiftyTwoWeekLow").asDouble());
            }

            // Update name if not set
            if (stock.getName() == null || stock.getName().isEmpty()) {
                if (meta.has("shortName")) {
                    stock.setName(meta.get("shortName").asText());
                } else if (meta.has("longName")) {
                    stock.setName(meta.get("longName").asText());
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

            // Fetch market cap if missing (from quote API)
            if (stock.getMarketCap() == null) {
                fetchMarketCap(stock, yahooSymbol);
            }

            if (updated) {
                stock.setLastUpdated(LocalDateTime.now());
                stock.setHigh52WeekUpdated(LocalDateTime.now());
                repository.save(stock);
            }

            return updated;

        } catch (Exception e) {
            // Silent fail for parallel processing
            return false;
        }
    }

    /**
     * Fetch market cap from Yahoo Finance quote API.
     * This is needed because the chart API doesn't include market cap.
     */
    private void fetchMarketCap(StockAnalytics stock, String yahooSymbol) {
        try {
            String apiUrl = "https://query1.finance.yahoo.com/v6/finance/quote?symbols=" + yahooSymbol;

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
            JsonNode quotes = root.path("quoteResponse").path("result");

            if (quotes.isArray() && quotes.size() > 0) {
                JsonNode quote = quotes.get(0);

                // Market cap (in raw value, convert to crores)
                if (quote.has("marketCap") && !quote.get("marketCap").isNull()) {
                    double marketCapRaw = quote.get("marketCap").asDouble();
                    // Convert to crores (1 crore = 10 million = 10,000,000)
                    double marketCapCrores = marketCapRaw / 10000000;
                    stock.setMarketCap(Math.round(marketCapCrores * 100.0) / 100.0);
                    log.debug("Fetched market cap for {}: {} crores", yahooSymbol, stock.getMarketCap());
                }

                // Also fetch sector/industry if missing
                if (stock.getSector() == null && quote.has("sector") && !quote.get("sector").isNull()) {
                    stock.setSector(quote.get("sector").asText());
                }
                if (stock.getIndustry() == null && quote.has("industry") && !quote.get("industry").isNull()) {
                    stock.setIndustry(quote.get("industry").asText());
                }
                if (stock.getSector() != null || stock.getIndustry() != null) {
                    stock.setSectorUpdated(LocalDateTime.now());
                }
            }

        } catch (Exception e) {
            // Silent fail - market cap is optional
            log.debug("Could not fetch market cap for {}: {}", yahooSymbol, e.getMessage());
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
     * Convert NSE ticker format to Yahoo Finance format.
     * NSE:TCS -> TCS.NS
     */
    private String toYahooSymbol(String ticker) {
        if (ticker == null) return null;
        String clean = ticker.trim().toUpperCase();
        if (clean.startsWith("NSE:")) {
            clean = clean.substring(4);
        } else if (clean.startsWith("BSE:")) {
            clean = clean.substring(4);
        }
        return clean + ".NS";
    }

    /**
     * Manual trigger for price update (for testing).
     */
    public int triggerPriceUpdate() {
        log.info("Manual Yahoo price update triggered");
        return updateYahooStockPrices();
    }

    /**
     * Update prices for specific tickers from Yahoo Finance, regardless of source.
     * Used by the "Refresh Prices" button on the dashboard.
     */
    public int updatePricesForTickers(List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) return 0;

        log.info("Refreshing Yahoo prices for {} tickers", tickers.size());

        // Load stocks from DB, create new entities for any missing tickers
        List<StockAnalytics> stocks = new ArrayList<>();
        for (String ticker : tickers) {
            String normalized = ticker.trim().toUpperCase();
            StockAnalytics stock = repository.findById(normalized).orElse(null);
            if (stock == null) {
                // Try with NSE: prefix
                stock = repository.findById("NSE:" + normalized).orElse(null);
            }
            if (stock != null) {
                stocks.add(stock);
            }
        }

        if (stocks.isEmpty()) {
            log.warn("No matching stocks found in DB for tickers: {}", tickers);
            return 0;
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(stocks.size(), 10));
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();

        for (StockAnalytics stock : stocks) {
            futures.add(CompletableFuture.supplyAsync(() -> updateStockPrice(stock), executor));
        }

        int success = 0;
        for (CompletableFuture<Boolean> f : futures) {
            try {
                if (f.get(30, TimeUnit.SECONDS)) success++;
            } catch (Exception e) { /* continue */ }
        }

        executor.shutdown();
        log.info("Refreshed {}/{} tickers from Yahoo", success, stocks.size());
        return success;
    }
}
