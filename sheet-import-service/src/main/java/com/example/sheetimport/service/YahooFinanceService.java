package com.example.sheetimport.service;

import com.example.sheetimport.model.StockAnalytics;
import com.example.sheetimport.repository.StockAnalyticsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service to fetch 52-week high/low data from Yahoo Finance API.
 * Yahoo Finance provides yearHigh and yearLow for stocks.
 */
@Service
public class YahooFinanceService {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceService.class);

    private final StockAnalyticsRepository repository;

    public YahooFinanceService(StockAnalyticsRepository repository) {
        this.repository = repository;
    }

    /**
     * Convert NSE ticker format to Yahoo Finance format.
     * NSE:TCS -> TCS.NS
     * TCS -> TCS.NS
     */
    public String toYahooSymbol(String ticker) {
        if (ticker == null) return null;
        String clean = ticker.trim().toUpperCase();
        // Remove NSE: or BSE: prefix if present
        if (clean.startsWith("NSE:")) {
            clean = clean.substring(4);
        } else if (clean.startsWith("BSE:")) {
            clean = clean.substring(4);
        }
        // Add .NS suffix for NSE stocks
        return clean + ".NS";
    }

    /**
     * Fetch 52-week high/low for a single stock from Yahoo Finance.
     * Uses direct HTTP calls with proper headers to avoid rate limiting.
     * Returns true if data was successfully fetched and updated.
     */
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 10000; // 10 seconds between retries
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public boolean fetch52WeekData(String ticker) {
        String yahooSymbol = toYahooSymbol(ticker);
        if (yahooSymbol == null) return false;

        // Retry logic for rate limiting (429 errors)
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("Fetching Yahoo data for symbol: {} (attempt {}/{})", yahooSymbol, attempt, MAX_RETRIES);

                // Use Yahoo Finance v8 quote API with proper headers
                String apiUrl = "https://query1.finance.yahoo.com/v8/finance/chart/" + yahooSymbol
                        + "?interval=1d&range=1y";

                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                // Set headers to mimic browser request (helps avoid rate limiting)
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");

                int responseCode = conn.getResponseCode();

                if (responseCode == 429) {
                    log.warn("Rate limited (429) for {}. Attempt {}/{}. Waiting {}ms...",
                            yahooSymbol, attempt, MAX_RETRIES, RETRY_DELAY_MS);
                    if (attempt < MAX_RETRIES) {
                        Thread.sleep(RETRY_DELAY_MS);
                        continue;
                    }
                    return false;
                }

                if (responseCode != 200) {
                    log.warn("HTTP {} for {}", responseCode, yahooSymbol);
                    return false;
                }

                // Read response
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }

                // Parse JSON response
                JsonNode root = objectMapper.readTree(response.toString());
                JsonNode meta = root.path("chart").path("result").get(0).path("meta");

                Double yearHigh = null;
                Double yearLow = null;

                if (meta.has("fiftyTwoWeekHigh")) {
                    yearHigh = meta.get("fiftyTwoWeekHigh").asDouble();
                }
                if (meta.has("fiftyTwoWeekLow")) {
                    yearLow = meta.get("fiftyTwoWeekLow").asDouble();
                }

                log.info("Yahoo data for {}: yearHigh={}, yearLow={}", yahooSymbol, yearHigh, yearLow);

                if (yearHigh == null && yearLow == null) {
                    log.warn("No 52W data in response for {}", yahooSymbol);
                    return false;
                }

                // Update database - try to find by ticker with and without NSE: prefix
                String tickerKey = ticker;
                Optional<StockAnalytics> optStock = repository.findById(tickerKey);

                // If not found, try with NSE: prefix
                if (!optStock.isPresent() && !ticker.startsWith("NSE:")) {
                    tickerKey = "NSE:" + ticker;
                    optStock = repository.findById(tickerKey);
                }

                // If still not found, try without prefix
                if (!optStock.isPresent() && ticker.startsWith("NSE:")) {
                    tickerKey = ticker.substring(4);
                    optStock = repository.findById(tickerKey);
                }

                if (optStock.isPresent()) {
                    StockAnalytics sa = optStock.get();
                    if (yearHigh != null) {
                        sa.setHigh52Week(yearHigh);
                    }
                    if (yearLow != null) {
                        sa.setLow52Week(yearLow);
                    }
                    sa.setHigh52WeekUpdated(LocalDateTime.now());
                    repository.save(sa);
                    log.info("Updated 52W for {}: high={}, low={}", tickerKey, yearHigh, yearLow);
                    return true;
                } else {
                    log.warn("Stock not found in DB for ticker: {} (tried variants)", ticker);
                }
                return false;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.error("Error fetching Yahoo data for {}: {}", yahooSymbol, e.getMessage(), e);
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }

        log.error("Failed to fetch Yahoo data for {} after {} attempts", yahooSymbol, MAX_RETRIES);
        return false;
    }

    /**
     * Fetch 52-week high/low for all stocks in the database.
     * Uses parallel processing with v8 chart API - ~10x faster than sequential.
     */
    public int updateAll52WeekData() {
        List<StockAnalytics> allStocks = repository.findAll();
        log.info("Starting FAST 52W high/low update for {} stocks (parallel)", allStocks.size());

        int parallelThreads = 10; // Number of concurrent requests
        ExecutorService executor = Executors.newFixedThreadPool(parallelThreads);

        List<CompletableFuture<Boolean>> futures = new ArrayList<>();

        for (StockAnalytics stock : allStocks) {
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                return fetchAndUpdate52WeekData(stock);
            }, executor);
            futures.add(future);
        }

        // Wait for all to complete and count successes
        int totalSuccess = 0;
        int processed = 0;
        for (CompletableFuture<Boolean> future : futures) {
            try {
                if (future.get(30, TimeUnit.SECONDS)) {
                    totalSuccess++;
                }
                processed++;
                if (processed % 200 == 0) {
                    log.info("Progress: {}/{} processed, {} updated", processed, allStocks.size(), totalSuccess);
                }
            } catch (Exception e) {
                // Continue with next
            }
        }

        executor.shutdown();
        log.info("FAST 52W update completed. Updated {}/{} stocks", totalSuccess, allStocks.size());
        return totalSuccess;
    }

    /**
     * Fetch 52W data and update stock in DB. Used for parallel processing.
     */
    private boolean fetchAndUpdate52WeekData(StockAnalytics stock) {
        String ticker = stock.getTicker();
        String yahooSymbol = toYahooSymbol(ticker);
        if (yahooSymbol == null) return false;

        try {
            String apiUrl = "https://query1.finance.yahoo.com/v8/finance/chart/" + yahooSymbol
                    + "?interval=1d&range=5d";

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

            JsonNode meta = resultArray.get(0).path("meta");

            Double yearHigh = meta.has("fiftyTwoWeekHigh") ? meta.get("fiftyTwoWeekHigh").asDouble() : null;
            Double yearLow = meta.has("fiftyTwoWeekLow") ? meta.get("fiftyTwoWeekLow").asDouble() : null;

            if (yearHigh != null || yearLow != null) {
                if (yearHigh != null) stock.setHigh52Week(yearHigh);
                if (yearLow != null) stock.setLow52Week(yearLow);
                stock.setHigh52WeekUpdated(LocalDateTime.now());
                repository.save(stock);
                return true;
            }

        } catch (Exception e) {
            // Silent fail for parallel processing
        }
        return false;
    }

    /**
     * Fetch sector and industry data for a single stock from NSE India API.
     * Yahoo Finance quoteSummary API now requires crumb authentication.
     * NSE API provides industry data which we use along with sector mapping.
     * Returns true if data was successfully fetched and updated.
     */
    public boolean fetchSectorData(String ticker) {
        // Extract pure ticker (remove NSE: or BSE: prefix)
        String nseSymbol = ticker;
        if (nseSymbol.startsWith("NSE:")) {
            nseSymbol = nseSymbol.substring(4);
        } else if (nseSymbol.startsWith("BSE:")) {
            nseSymbol = nseSymbol.substring(4);
        }
        nseSymbol = nseSymbol.toUpperCase();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("Fetching sector data from NSE for symbol: {} (attempt {}/{})", nseSymbol, attempt, MAX_RETRIES);

                // Use NSE India API for quote equity
                String apiUrl = "https://www.nseindia.com/api/quote-equity?symbol=" + nseSymbol;

                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                // Set headers to mimic browser request (NSE requires this)
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");

                int responseCode = conn.getResponseCode();

                if (responseCode == 429) {
                    log.warn("Rate limited (429) for {}. Attempt {}/{}. Waiting {}ms...",
                            nseSymbol, attempt, MAX_RETRIES, RETRY_DELAY_MS);
                    if (attempt < MAX_RETRIES) {
                        Thread.sleep(RETRY_DELAY_MS);
                        continue;
                    }
                    return false;
                }

                if (responseCode != 200) {
                    log.warn("HTTP {} for sector data {}", responseCode, nseSymbol);
                    return false;
                }

                // Read response
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }

                // Parse JSON response
                JsonNode root = objectMapper.readTree(response.toString());

                // Get industry from info section
                String industry = null;
                if (root.has("info") && root.path("info").has("industry")) {
                    industry = root.path("info").get("industry").asText();
                }

                // Get sector index from metadata (e.g., "NIFTY IT", "NIFTY BANK")
                String sector = null;
                if (root.has("metadata") && root.path("metadata").has("pdSectorInd")) {
                    String sectorIndex = root.path("metadata").get("pdSectorInd").asText();
                    sector = mapSectorIndexToSector(sectorIndex, industry);
                }

                log.info("Sector data for {}: sector={}, industry={}", nseSymbol, sector, industry);

                if (sector == null && industry == null) {
                    log.warn("No sector/industry data in response for {}", nseSymbol);
                    return false;
                }

                // Update database - try to find by ticker with and without NSE: prefix
                String tickerKey = ticker;
                Optional<StockAnalytics> optStock = repository.findById(tickerKey);

                if (!optStock.isPresent() && !ticker.startsWith("NSE:")) {
                    tickerKey = "NSE:" + ticker;
                    optStock = repository.findById(tickerKey);
                }

                if (!optStock.isPresent() && ticker.startsWith("NSE:")) {
                    tickerKey = ticker.substring(4);
                    optStock = repository.findById(tickerKey);
                }

                if (optStock.isPresent()) {
                    StockAnalytics sa = optStock.get();
                    if (sector != null) {
                        sa.setSector(sector);
                    }
                    if (industry != null) {
                        sa.setIndustry(industry);
                    }
                    sa.setSectorUpdated(LocalDateTime.now());
                    repository.save(sa);
                    log.info("Updated sector for {}: sector={}, industry={}", tickerKey, sector, industry);
                    return true;
                } else {
                    log.warn("Stock not found in DB for ticker: {} (tried variants)", ticker);
                }
                return false;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.error("Error fetching sector data for {}: {}", nseSymbol, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }

        log.error("Failed to fetch sector data for {} after {} attempts", nseSymbol, MAX_RETRIES);
        return false;
    }

    /**
     * Public method to test sector mapping logic.
     */
    public String testSectorMapping(String sectorIndex, String industry) {
        return mapSectorIndexToSector(sectorIndex, industry);
    }

    /**
     * Get stock by ticker from database.
     */
    public StockAnalytics getStockByTicker(String ticker) {
        return repository.findById(ticker).orElse(null);
    }

    /**
     * Map NSE sector index to a standard sector name.
     * Also uses industry as fallback for sector categorization.
     */
    private String mapSectorIndexToSector(String sectorIndex, String industry) {
        if (sectorIndex == null) {
            return deriveSectorFromIndustry(industry);
        }

        String upper = sectorIndex.toUpperCase();

        // Map common sector indices to sector names
        if (upper.contains("IT") || upper.contains("DIGITAL")) return "Technology";
        if (upper.contains("BANK") || upper.contains("FINANCIAL") || upper.contains("FIN SERVICE")) return "Financial Services";
        if (upper.contains("PHARMA") || upper.contains("HEALTHCARE")) return "Healthcare";
        if (upper.contains("AUTO")) return "Automobile";
        if (upper.contains("METAL") || upper.contains("STEEL")) return "Metals & Mining";
        if (upper.contains("ENERGY") || upper.contains("OIL") || upper.contains("GAS")) return "Energy";
        if (upper.contains("FMCG") || upper.contains("CONSUMER")) return "Consumer Goods";
        if (upper.contains("REALTY") || upper.contains("INFRA")) return "Real Estate & Infrastructure";
        if (upper.contains("TELECOM") || upper.contains("MEDIA")) return "Telecom & Media";
        if (upper.contains("PSU") || upper.contains("PSE")) return "Public Sector";

        // Fallback to industry-based mapping
        return deriveSectorFromIndustry(industry);
    }

    /**
     * Derive sector from industry name when sector index is not available.
     */
    private String deriveSectorFromIndustry(String industry) {
        if (industry == null) return "Diversified";

        String upper = industry.toUpperCase();

        if (upper.contains("SOFTWARE") || upper.contains("IT ") || upper.contains("COMPUTER") || upper.contains("TECH")) return "Technology";
        if (upper.contains("BANK") || upper.contains("FINANCE") || upper.contains("INSURANCE") || upper.contains("NBFC")) return "Financial Services";
        if (upper.contains("PHARMA") || upper.contains("HEALTHCARE") || upper.contains("HOSPITAL") || upper.contains("DRUG")) return "Healthcare";
        if (upper.contains("AUTO") || upper.contains("VEHICLE") || upper.contains("TYRE")) return "Automobile";
        if (upper.contains("STEEL") || upper.contains("METAL") || upper.contains("MINING") || upper.contains("ALUMINIUM")) return "Metals & Mining";
        if (upper.contains("OIL") || upper.contains("GAS") || upper.contains("REFINER") || upper.contains("PETROLEUM") || upper.contains("POWER") || upper.contains("ENERGY")) return "Energy";
        if (upper.contains("FMCG") || upper.contains("FOOD") || upper.contains("BEVERAGE") || upper.contains("PERSONAL CARE")) return "Consumer Goods";
        if (upper.contains("CEMENT") || upper.contains("CONSTRUCTION") || upper.contains("REALTY") || upper.contains("INFRASTRUCTURE")) return "Real Estate & Infrastructure";
        if (upper.contains("TELECOM") || upper.contains("MEDIA") || upper.contains("ENTERTAINMENT")) return "Telecom & Media";
        if (upper.contains("TEXTILE") || upper.contains("APPAREL")) return "Textiles";
        if (upper.contains("CHEMICAL") || upper.contains("FERTILIZER")) return "Chemicals";
        if (upper.contains("AGRI") || upper.contains("SUGAR")) return "Agriculture";

        return "Diversified";
    }

    /**
     * Fetch sector data for all stocks in the database.
     * Uses FAST parallel processing - fetches directly without retry logic.
     */
    public int updateAllSectorData() {
        List<StockAnalytics> allStocks = repository.findAll();
        log.info("Starting FAST sector data update for {} stocks (parallel)", allStocks.size());

        int parallelThreads = 10; // More threads for faster processing
        ExecutorService executor = Executors.newFixedThreadPool(parallelThreads);

        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        int skipped = 0;

        for (StockAnalytics stock : allStocks) {
            // Skip if already has sector data updated recently (within 7 days)
            if (stock.getSector() != null && stock.getSectorUpdated() != null
                    && stock.getSectorUpdated().isAfter(LocalDateTime.now().minusDays(7))) {
                skipped++;
                continue;
            }

            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                return fetchAndUpdateSectorDataFast(stock);
            }, executor);
            futures.add(future);
        }

        log.info("Skipped {} stocks (already updated). Processing {} stocks...", skipped, futures.size());

        int totalSuccess = 0;
        int processed = 0;
        for (CompletableFuture<Boolean> future : futures) {
            try {
                if (future.get(30, TimeUnit.SECONDS)) {
                    totalSuccess++;
                }
                processed++;
                if (processed % 200 == 0) {
                    log.info("Progress: {}/{} processed, {} updated", processed, futures.size(), totalSuccess);
                }
            } catch (Exception e) {
                // Continue with next
            }
        }

        executor.shutdown();
        log.info("FAST sector data update completed. Updated {}/{} stocks", totalSuccess, allStocks.size());
        return totalSuccess;
    }

    /**
     * Fast fetch and update sector data - single attempt, no retry, minimal logging.
     */
    private boolean fetchAndUpdateSectorDataFast(StockAnalytics stock) {
        String ticker = stock.getTicker();
        String nseSymbol = ticker;
        if (nseSymbol.startsWith("NSE:")) {
            nseSymbol = nseSymbol.substring(4);
        } else if (nseSymbol.startsWith("BSE:")) {
            nseSymbol = nseSymbol.substring(4);
        }
        nseSymbol = nseSymbol.toUpperCase();

        try {
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

            String industry = null;
            if (root.has("info") && root.path("info").has("industry")) {
                industry = root.path("info").get("industry").asText();
            }

            String sector = null;
            if (root.has("metadata") && root.path("metadata").has("pdSectorInd")) {
                String sectorIndex = root.path("metadata").get("pdSectorInd").asText();
                sector = mapSectorIndexToSector(sectorIndex, industry);
            }

            if (sector != null || industry != null) {
                if (sector != null) stock.setSector(sector);
                if (industry != null) stock.setIndustry(industry);
                stock.setSectorUpdated(LocalDateTime.now());
                repository.save(stock);
                return true;
            }

        } catch (Exception e) {
            // Silent fail for fast processing
        }
        return false;
    }

    /**
     * Fetch NIFTY 50 index data from Yahoo Finance.
     * Symbol: ^NSEI for NIFTY 50
     * Returns a map containing current price and returns for various periods.
     */
    public Map<String, Object> fetchNifty50Data() {
        return fetchIndexData("^NSEI", "NIFTY 50");
    }

    /**
     * Fetch all major Indian indices for portfolio comparison.
     * Returns data for NIFTY 50, NIFTY 500, NIFTY Midcap 100, NIFTY Smallcap 100
     */
    public Map<String, Object> fetchAllIndicesData() {
        Map<String, Object> result = new HashMap<>();

        // Define indices with their Yahoo symbols
        // Verified working Yahoo Finance symbols for Indian indices
        Map<String, String> indices = new LinkedHashMap<>();
        indices.put("NIFTY 50", "^NSEI");
        indices.put("NIFTY Midcap 100", "^CRSMID");
        indices.put("NIFTY Smallcap 100", "^CNXSC");
        indices.put("SENSEX", "^BSESN");

        List<Map<String, Object>> indicesData = new ArrayList<>();

        for (Map.Entry<String, String> entry : indices.entrySet()) {
            String name = entry.getKey();
            String symbol = entry.getValue();

            Map<String, Object> indexData = fetchIndexData(symbol, name);
            if (indexData.containsKey("success") && Boolean.TRUE.equals(indexData.get("success"))) {
                indicesData.add(indexData);
            } else {
                // Add placeholder with name even if fetch failed
                Map<String, Object> placeholder = new HashMap<>();
                placeholder.put("name", name);
                placeholder.put("symbol", symbol);
                placeholder.put("error", indexData.get("error"));
                placeholder.put("success", false);
                indicesData.add(placeholder);
            }
        }

        result.put("indices", indicesData);
        result.put("fetchedAt", java.time.LocalDateTime.now().toString());

        return result;
    }

    /**
     * Fetch index data from Yahoo Finance.
     * Calculates returns for 1M, 3M, 6M, 1Y periods.
     */
    public Map<String, Object> fetchIndexData(String symbol, String name) {
        Map<String, Object> result = new HashMap<>();
        result.put("symbol", symbol);
        result.put("name", name);

        try {
            // Fetch 1 year of data to calculate all periods
            String apiUrl = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
                    + "?interval=1d&range=1y";

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log.warn("HTTP {} fetching {}", responseCode, symbol);
                result.put("error", "HTTP " + responseCode);
                return result;
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            JsonNode root = objectMapper.readTree(response.toString());
            JsonNode resultNode = root.path("chart").path("result").get(0);
            JsonNode meta = resultNode.path("meta");
            JsonNode timestamps = resultNode.path("timestamp");
            JsonNode closes = resultNode.path("indicators").path("quote").get(0).path("close");

            // Get current price
            double regularMarketPrice = meta.has("regularMarketPrice")
                    ? meta.get("regularMarketPrice").asDouble() : 0;
            result.put("currentPrice", regularMarketPrice);

            // Calculate returns for different periods
            int dataPoints = timestamps.size();
            if (dataPoints > 0 && closes.size() > 0) {
                double latestClose = 0;
                // Find latest non-null close
                for (int i = closes.size() - 1; i >= 0; i--) {
                    if (!closes.get(i).isNull()) {
                        latestClose = closes.get(i).asDouble();
                        break;
                    }
                }

                // Calculate returns for different periods
                // Approximate trading days: 1M=21, 3M=63, 6M=126, 1Y=252
                result.put("return1M", calculatePeriodReturn(closes, 21, latestClose));
                result.put("return3M", calculatePeriodReturn(closes, 63, latestClose));
                result.put("return6M", calculatePeriodReturn(closes, 126, latestClose));
                result.put("return1Y", calculatePeriodReturn(closes, 252, latestClose));

                // Also store price at each period start for reference
                result.put("price1MAgo", getPriceAtPeriod(closes, 21));
                result.put("price3MAgo", getPriceAtPeriod(closes, 63));
                result.put("price6MAgo", getPriceAtPeriod(closes, 126));
                result.put("price1YAgo", getPriceAtPeriod(closes, 252));
            }

            result.put("success", true);
            log.info("Fetched {} data: currentPrice={}, 1Y return={}%",
                    name, regularMarketPrice, result.get("return1Y"));

        } catch (Exception e) {
            log.error("Error fetching index data for {}: {}", symbol, e.getMessage());
            result.put("error", e.getMessage());
            result.put("success", false);
        }

        return result;
    }

    /**
     * Calculate percentage return for a period.
     */
    private Double calculatePeriodReturn(JsonNode closes, int daysAgo, double currentPrice) {
        if (closes.size() < daysAgo + 1) {
            daysAgo = closes.size() - 1;
        }
        if (daysAgo <= 0) return null;

        Double pastPrice = getPriceAtPeriod(closes, daysAgo);
        if (pastPrice == null || pastPrice == 0) return null;

        return ((currentPrice - pastPrice) / pastPrice) * 100;
    }

    /**
     * Get price at N trading days ago.
     */
    private Double getPriceAtPeriod(JsonNode closes, int daysAgo) {
        int targetIndex = closes.size() - 1 - daysAgo;
        if (targetIndex < 0) targetIndex = 0;

        // Find non-null close near target
        for (int i = targetIndex; i <= targetIndex + 5 && i < closes.size(); i++) {
            if (!closes.get(i).isNull()) {
                return closes.get(i).asDouble();
            }
        }
        // Try looking backwards
        for (int i = targetIndex; i >= 0 && i > targetIndex - 5; i--) {
            if (!closes.get(i).isNull()) {
                return closes.get(i).asDouble();
            }
        }
        return null;
    }

    /**
     * Fetch 52-week data for stocks that haven't been updated today.
     * More efficient for incremental updates.
     */
    public int updateStale52WeekData() {
        List<StockAnalytics> allStocks = repository.findAll();
        LocalDateTime today = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);

        int successCount = 0;
        int skipped = 0;

        for (StockAnalytics stock : allStocks) {
            // Skip if already updated today
            if (stock.getHigh52WeekUpdated() != null && stock.getHigh52WeekUpdated().isAfter(today)) {
                skipped++;
                continue;
            }

            if (fetch52WeekData(stock.getTicker())) {
                successCount++;
            }

            // Small delay to avoid rate limiting
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("Stale 52W update: updated {}, skipped {} (already current)", successCount, skipped);
        return successCount;
    }

    /**
     * Fetch historical daily data for all major indices.
     * Returns normalized data (base 100) for comparison.
     * @param fromDateStr Optional start date (YYYY-MM-DD). If provided, data is filtered and normalized from this date.
     */
    public Map<String, Object> fetchAllIndicesHistoricalData(String fromDateStr) {
        Map<String, Object> result = new HashMap<>();

        // Parse fromDate if provided
        java.time.LocalDate fromDate = null;
        if (fromDateStr != null && !fromDateStr.isEmpty()) {
            try {
                fromDate = java.time.LocalDate.parse(fromDateStr);
                result.put("fromDate", fromDateStr);
            } catch (Exception e) {
                log.warn("Invalid fromDate format: {}", fromDateStr);
            }
        }

        // Define indices with their Yahoo symbols
        // Verified working Yahoo Finance symbols for Indian indices
        Map<String, String> indices = new LinkedHashMap<>();
        indices.put("NIFTY 50", "^NSEI");
        indices.put("NIFTY Midcap 100", "^CRSMID");
        indices.put("NIFTY Smallcap 100", "^CNXSC");
        indices.put("SENSEX", "^BSESN");

        List<Map<String, Object>> indicesData = new ArrayList<>();

        for (Map.Entry<String, String> entry : indices.entrySet()) {
            String name = entry.getKey();
            String symbol = entry.getValue();

            Map<String, Object> indexData = fetchIndexHistoricalData(symbol, name, fromDate);
            indicesData.add(indexData);
        }

        result.put("indices", indicesData);
        result.put("fetchedAt", LocalDateTime.now().toString());

        return result;
    }

    /**
     * Fetch historical daily data for a single index.
     * Returns timestamps and normalized values (base 100).
     * @param fromDate Optional start date. If provided, data is filtered and normalized from this date.
     */
    public Map<String, Object> fetchIndexHistoricalData(String symbol, String name, java.time.LocalDate fromDate) {
        Map<String, Object> result = new HashMap<>();
        result.put("symbol", symbol);
        result.put("name", name);

        try {
            // Fetch 1 year of daily data
            String apiUrl = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
                    + "?interval=1d&range=1y";

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log.warn("HTTP {} fetching historical data for {}", responseCode, symbol);
                result.put("error", "HTTP " + responseCode);
                result.put("success", false);
                return result;
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            JsonNode root = objectMapper.readTree(response.toString());
            JsonNode resultNode = root.path("chart").path("result").get(0);
            JsonNode timestamps = resultNode.path("timestamp");
            JsonNode closes = resultNode.path("indicators").path("quote").get(0).path("close");

            if (!timestamps.isArray() || timestamps.size() == 0) {
                result.put("error", "No data");
                result.put("success", false);
                return result;
            }

            // Build arrays of dates and values (filtered by fromDate if provided)
            List<String> allDates = new ArrayList<>();
            List<Double> allCloses = new ArrayList<>();

            double lastValidClose = 0;
            for (int i = 0; i < timestamps.size(); i++) {
                if (closes.get(i).isNull()) {
                    // Carry forward previous day's close instead of skipping
                    if (lastValidClose == 0) continue;
                } else {
                    lastValidClose = closes.get(i).asDouble();
                }

                long timestamp = timestamps.get(i).asLong();
                double closePrice = lastValidClose;

                java.time.Instant instant = java.time.Instant.ofEpochSecond(timestamp);
                java.time.LocalDate date = instant.atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();

                // Filter by fromDate if provided
                if (fromDate != null && date.isBefore(fromDate)) {
                    continue;
                }

                allDates.add(date.toString());
                allCloses.add(closePrice);
            }

            if (allDates.isEmpty()) {
                result.put("error", "No data after fromDate");
                result.put("success", false);
                return result;
            }

            // Find base price (first price after filtering) for normalization
            Double basePrice = allCloses.get(0);

            if (basePrice == null || basePrice == 0) {
                result.put("error", "No valid price data");
                result.put("success", false);
                return result;
            }

            // Calculate normalized values
            List<Double> normalizedValues = new ArrayList<>();
            List<Double> rawValues = new ArrayList<>();

            for (Double closePrice : allCloses) {
                double normalized = (closePrice / basePrice) * 100;
                normalizedValues.add(Math.round(normalized * 100.0) / 100.0);
                rawValues.add(Math.round(closePrice * 100.0) / 100.0);
            }

            result.put("dates", allDates);
            result.put("normalizedValues", normalizedValues);
            result.put("rawValues", rawValues);
            result.put("basePrice", Math.round(basePrice * 100.0) / 100.0);
            result.put("baseDate", allDates.get(0));
            result.put("dataPoints", allDates.size());
            result.put("success", true);

            log.info("Fetched {} historical data points for {} (from {})",
                    allDates.size(), name, fromDate != null ? fromDate : "1 year ago");

        } catch (Exception e) {
            log.error("Error fetching historical data for {}: {}", symbol, e.getMessage());
            result.put("error", e.getMessage());
            result.put("success", false);
        }

        return result;
    }
}
