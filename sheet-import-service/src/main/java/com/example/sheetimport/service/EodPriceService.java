package com.example.sheetimport.service;

import com.example.sheetimport.model.AnchorPriceCache;
import com.example.sheetimport.model.StockAnalytics;
import com.example.sheetimport.repository.AnchorPriceCacheRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service to fetch and store EOD (End of Day) prices for all stocks.
 * Stores prices in anchor_price_cache table for historical lookups.
 */
@Service
public class EodPriceService {

    private static final Logger log = LoggerFactory.getLogger(EodPriceService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 2000;
    private static final int PARALLEL_THREADS = 50;  // High parallelism for speed
    // No batch delay - Yahoo can handle concurrent requests

    private final StockAnalyticsRepository stockRepository;
    private final AnchorPriceCacheRepository cacheRepository;

    // Track ongoing operations
    private volatile boolean isRunning = false;
    private final AtomicInteger progress = new AtomicInteger(0);
    private volatile int totalStocks = 0;
    private volatile String currentOperationDate = null;

    public EodPriceService(StockAnalyticsRepository stockRepository,
                           AnchorPriceCacheRepository cacheRepository) {
        this.stockRepository = stockRepository;
        this.cacheRepository = cacheRepository;
    }

    /**
     * Fetch EOD prices for ALL stocks for a specific date.
     * Used for prefilling event dates (Budget, RBI Policy, etc.)
     *
     * @param date The date to fetch prices for
     * @param force If true, re-fetch even if prices exist
     * @return Map with status and count
     */
    public Map<String, Object> fetchPricesForDate(LocalDate date, boolean force) {
        Map<String, Object> result = new HashMap<>();
        result.put("date", date.toString());

        // Check if already cached
        if (!force && cacheRepository.existsByDate(date)) {
            long cachedCount = cacheRepository.countByDate(date);
            long totalStocksInDb = stockRepository.count();
            result.put("status", "already_cached");
            result.put("count", cachedCount);
            result.put("totalStocksInDb", totalStocksInDb);
            result.put("message", cachedCount < totalStocksInDb
                    ? "Partial cache - use force=true to re-fetch all"
                    : "Fully cached");
            log.info("Prices for {} already cached ({}/{} stocks)", date, cachedCount, totalStocksInDb);
            return result;
        }

        if (isRunning) {
            result.put("status", "already_running");
            result.put("progress", progress.get() + "/" + totalStocks);
            return result;
        }

        // Get all stocks
        List<StockAnalytics> allStocks = stockRepository.findAll();
        if (allStocks.isEmpty()) {
            result.put("status", "no_stocks");
            result.put("count", 0);
            return result;
        }

        log.info("Fetching EOD prices for {} stocks on {}", allStocks.size(), date);

        isRunning = true;
        totalStocks = allStocks.size();
        progress.set(0);

        try {
            int fetched = fetchPricesParallel(allStocks, date);
            result.put("status", "success");
            result.put("count", fetched);
            result.put("total", allStocks.size());
            log.info("Fetched {} EOD prices for {}", fetched, date);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
            log.error("Error fetching EOD prices for {}: {}", date, e.getMessage());
        } finally {
            isRunning = false;
        }

        return result;
    }

    /**
     * Backfill historical prices for a date range.
     * Runs asynchronously and returns immediately.
     *
     * @param fromDate Start date (inclusive)
     * @param toDate End date (inclusive)
     * @return Status map
     */
    public Map<String, Object> backfillHistorical(LocalDate fromDate, LocalDate toDate) {
        Map<String, Object> result = new HashMap<>();

        if (isRunning) {
            result.put("status", "already_running");
            result.put("progress", progress.get() + "/" + totalStocks);
            return result;
        }

        // Validate dates
        if (fromDate.isAfter(toDate)) {
            result.put("status", "error");
            result.put("error", "fromDate must be before toDate");
            return result;
        }

        if (toDate.isAfter(LocalDate.now())) {
            toDate = LocalDate.now();
        }

        // Calculate trading days (skip weekends)
        List<LocalDate> tradingDays = new ArrayList<>();
        LocalDate current = fromDate;
        while (!current.isAfter(toDate)) {
            if (current.getDayOfWeek().getValue() <= 5) { // Mon-Fri
                tradingDays.add(current);
            }
            current = current.plusDays(1);
        }

        result.put("status", "started");
        result.put("fromDate", fromDate.toString());
        result.put("toDate", toDate.toString());
        result.put("tradingDays", tradingDays.size());

        // Run in background
        final List<LocalDate> finalTradingDays = tradingDays;
        final LocalDate finalToDate = toDate;
        CompletableFuture.runAsync(() -> {
            log.info("Starting backfill for {} trading days from {} to {}",
                    finalTradingDays.size(), fromDate, finalToDate);

            int completed = 0;
            for (LocalDate day : finalTradingDays) {
                try {
                    Map<String, Object> dayResult = fetchPricesForDate(day, false);
                    completed++;
                    log.info("Backfill progress: {}/{} days - {} status: {}",
                            completed, finalTradingDays.size(), day, dayResult.get("status"));

                    // Delay between days to avoid rate limiting
                    Thread.sleep(5000);
                } catch (Exception e) {
                    log.error("Error backfilling {}: {}", day, e.getMessage());
                }
            }

            log.info("Backfill completed. Processed {} days", completed);
        });

        return result;
    }

    /**
     * Fetch prices async - returns immediately and runs in background.
     * Frontend can poll getStatus() to track progress.
     */
    public Map<String, Object> fetchPricesForDateAsync(LocalDate date, boolean force) {
        Map<String, Object> result = new HashMap<>();
        result.put("date", date.toString());

        // Check if already cached
        if (!force && cacheRepository.existsByDate(date)) {
            long cachedCount = cacheRepository.countByDate(date);
            result.put("status", "already_cached");
            result.put("count", cachedCount);
            return result;
        }

        if (isRunning) {
            result.put("status", "already_running");
            result.put("progress", progress.get());
            result.put("total", totalStocks);
            return result;
        }

        // Get all stocks
        List<StockAnalytics> allStocks = stockRepository.findAll();
        if (allStocks.isEmpty()) {
            result.put("status", "no_stocks");
            result.put("count", 0);
            return result;
        }

        isRunning = true;
        totalStocks = allStocks.size();
        progress.set(0);
        currentOperationDate = date.toString();

        // Run in background
        CompletableFuture.runAsync(() -> {
            try {
                log.info("Async prefill started for {} - {} stocks", date, allStocks.size());
                int fetched = fetchPricesParallel(allStocks, date);
                log.info("Async prefill completed for {} - {} prices fetched", date, fetched);
            } catch (Exception e) {
                log.error("Async prefill failed for {}: {}", date, e.getMessage());
            } finally {
                isRunning = false;
            }
        });

        result.put("status", "started");
        result.put("total", allStocks.size());
        result.put("message", "Fetching prices in background. Poll /api/eod-prices/status for progress.");
        return result;
    }

    /**
     * Get current status of running operation.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("isRunning", isRunning);
        status.put("progress", progress.get());
        status.put("total", totalStocks);
        if (currentOperationDate != null) {
            status.put("date", currentOperationDate);
        }
        status.put("cachedDates", cacheRepository.findAllCachedDates());
        return status;
    }

    /**
     * Re-fetch all cached anchor dates with split-adjusted prices.
     * Runs in background, processing each date sequentially with force=true.
     */
    public Map<String, Object> refreshAllCachedDates() {
        Map<String, Object> result = new HashMap<>();

        if (isRunning) {
            result.put("status", "already_running");
            result.put("progress", progress.get() + "/" + totalStocks);
            return result;
        }

        List<LocalDate> cachedDates = cacheRepository.findAllCachedDates();
        if (cachedDates.isEmpty()) {
            result.put("status", "no_dates");
            result.put("message", "No cached dates found");
            return result;
        }

        result.put("status", "started");
        result.put("dates", cachedDates.stream().map(LocalDate::toString).toList());
        result.put("totalDates", cachedDates.size());
        result.put("message", "Re-fetching " + cachedDates.size() + " dates with split-adjusted prices");

        CompletableFuture.runAsync(() -> {
            log.info("Starting refresh of {} cached anchor dates", cachedDates.size());
            int completed = 0;
            for (LocalDate date : cachedDates) {
                try {
                    log.info("Refreshing anchor date {}/{}: {}", completed + 1, cachedDates.size(), date);
                    fetchPricesForDate(date, true);  // force=true to overwrite
                    completed++;
                    // Delay between dates to avoid rate limiting
                    Thread.sleep(5000);
                } catch (Exception e) {
                    log.error("Error refreshing {}: {}", date, e.getMessage());
                }
            }
            log.info("Refresh completed. Processed {} dates", completed);
        });

        return result;
    }

    /**
     * Cancel ongoing operation.
     */
    public void cancel() {
        log.info("Cancelling ongoing operation...");
        isRunning = false;
    }

    /**
     * Fetch prices for multiple stocks in parallel.
     */
    private int fetchPricesParallel(List<StockAnalytics> stocks, LocalDate date) {
        log.info("Starting parallel fetch for {} stocks on {} with {} threads",
                stocks.size(), date, PARALLEL_THREADS);

        Map<String, Double> prices = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_THREADS);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (StockAnalytics stock : stocks) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    String ticker = stock.getTicker();
                    Double price = fetchPriceFromYahoo(ticker, date);

                    if (price != null && price > 0) {
                        prices.put(ticker.toUpperCase().replace("NSE:", ""), price);
                    }

                    int prog = progress.incrementAndGet();
                    // Log progress every 200 stocks
                    if (prog % 200 == 0) {
                        log.info("Progress: {}/{} stocks processed, {} prices fetched",
                                prog, totalStocks, prices.size());
                    }
                } catch (Exception e) {
                    progress.incrementAndGet();
                    log.debug("Error fetching {}: {}", stock.getTicker(), e.getMessage());
                }
            }, executor);

            futures.add(future);
        }

        log.info("All {} futures submitted, waiting for completion...", futures.size());

        // Wait for all futures
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(15, TimeUnit.MINUTES);
            log.info("All futures completed. Fetched {} prices", prices.size());
        } catch (Exception e) {
            log.warn("Some fetches may have timed out: {} - got {} prices so far",
                    e.getMessage(), prices.size());
        } finally {
            executor.shutdown();
        }

        // Save to database
        log.info("Saving {} prices to database for {}", prices.size(), date);
        savePricesToDb(date, prices);

        return prices.size();
    }

    /**
     * Fetch price for a single stock from Yahoo Finance.
     */
    private Double fetchPriceFromYahoo(String ticker, LocalDate targetDate) {
        String yahooSymbol = toYahooSymbol(ticker);
        if (yahooSymbol == null) return null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                // Fetch a range around the target date
                LocalDate fromDate = targetDate.minusDays(5);
                LocalDate toDate = targetDate.plusDays(1);

                long fromTimestamp = fromDate.atStartOfDay(ZoneId.of("Asia/Kolkata")).toEpochSecond();
                long toTimestamp = toDate.atTime(23, 59, 59).atZone(ZoneId.of("Asia/Kolkata")).toEpochSecond();

                String apiUrl = "https://query1.finance.yahoo.com/v8/finance/chart/" + yahooSymbol
                        + "?period1=" + fromTimestamp
                        + "&period2=" + toTimestamp
                        + "&interval=1d";

                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                String[] userAgents = {
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"
                };
                conn.setRequestProperty("User-Agent", userAgents[attempt % userAgents.length]);
                conn.setRequestProperty("Accept", "application/json");

                int responseCode = conn.getResponseCode();

                if (responseCode == 429) {
                    // Rate limited - wait and retry
                    if (attempt < MAX_RETRIES) {
                        Thread.sleep(RETRY_DELAY_MS * attempt);
                        continue;
                    }
                    return null;
                }

                if (responseCode == 404) {
                    // Stock not found
                    return null;
                }

                if (responseCode != 200) {
                    return null;
                }

                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }

                return parseClosingPrice(response.toString(), targetDate, yahooSymbol);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Parse closing price from Yahoo response.
     * Uses adjclose (split-adjusted) if available, falls back to close.
     */
    private Double parseClosingPrice(String jsonResponse, LocalDate targetDate, String yahooSymbol) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode resultArray = root.path("chart").path("result");

            if (!resultArray.isArray() || resultArray.isEmpty()) {
                return null;
            }

            JsonNode result = resultArray.get(0);
            JsonNode timestamps = result.path("timestamp");

            // Prefer adjclose (split-adjusted) over close (unadjusted)
            JsonNode adjCloseNode = result.path("indicators").path("adjclose");
            JsonNode closes;
            if (adjCloseNode.isArray() && !adjCloseNode.isEmpty()
                    && adjCloseNode.get(0).has("adjclose")
                    && adjCloseNode.get(0).path("adjclose").isArray()) {
                closes = adjCloseNode.get(0).path("adjclose");
                log.debug("Using adjclose for {}", yahooSymbol);
            } else {
                closes = result.path("indicators").path("quote").get(0).path("close");
            }

            if (!timestamps.isArray() || timestamps.isEmpty()) {
                return null;
            }

            // Find the price on or closest to target date (prefer before target)
            Double closestPrice = null;
            long closestDiff = Long.MAX_VALUE;

            for (int i = 0; i < timestamps.size(); i++) {
                if (closes.get(i).isNull()) continue;

                long ts = timestamps.get(i).asLong();
                double closePrice = closes.get(i).asDouble();
                LocalDate date = Instant.ofEpochSecond(ts)
                        .atZone(ZoneId.of("Asia/Kolkata"))
                        .toLocalDate();

                long diff = Math.abs(ChronoUnit.DAYS.between(targetDate, date));

                if (date.equals(targetDate)) {
                    return closePrice; // Exact match
                } else if (date.isBefore(targetDate) && diff < closestDiff) {
                    closestDiff = diff;
                    closestPrice = closePrice;
                } else if (closestPrice == null && diff < closestDiff) {
                    closestDiff = diff;
                    closestPrice = closePrice;
                }
            }

            return closestPrice;

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Save prices to database.
     */
    private void savePricesToDb(LocalDate date, Map<String, Double> prices) {
        if (prices.isEmpty()) {
            return;
        }

        List<AnchorPriceCache> entities = new ArrayList<>();
        for (Map.Entry<String, Double> entry : prices.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                entities.add(AnchorPriceCache.of(entry.getKey(), date, entry.getValue()));
            }
        }

        try {
            cacheRepository.saveAll(entities);
            log.info("Saved {} prices to DB for {}", entities.size(), date);
        } catch (Exception e) {
            // Handle duplicate key errors gracefully
            log.debug("Some prices may already exist: {}", e.getMessage());
            // Try individual saves
            int saved = 0;
            for (AnchorPriceCache entity : entities) {
                try {
                    cacheRepository.save(entity);
                    saved++;
                } catch (Exception ignored) {}
            }
            log.info("Saved {} prices individually for {}", saved, date);
        }
    }

    /**
     * Convert ticker to Yahoo symbol.
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
}
