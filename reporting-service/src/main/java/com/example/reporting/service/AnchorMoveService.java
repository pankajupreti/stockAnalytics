package com.example.reporting.service;

import com.example.reporting.model.AnchorPriceCache;
import com.example.reporting.repository.AnchorPriceCacheRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service for fetching historical stock prices for "anchor" dates.
 * Used for calculating stock movement since significant events (Budget, RBI Policy, etc.)
 *
 * Caching Strategy:
 * - L1: Caffeine (in-memory) - ultra fast, limited size, volatile
 * - L2: Database (anchor_price_cache) - persistent, populated by EOD job
 *
 * NOTE: This service only READS from database.
 * The EOD job in sheet-import-service is responsible for populating the data.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnchorMoveService {

    private final AnchorPriceCacheRepository repository;

    /**
     * L1 Cache: In-memory Caffeine cache for hot dates.
     * Key: LocalDate (anchor date)
     * Value: Map<ticker, closePrice>
     */
    private final Cache<LocalDate, Map<String, Double>> l1Cache = Caffeine.newBuilder()
            .maximumSize(20)                        // Keep last 20 anchor dates
            .expireAfterAccess(2, TimeUnit.HOURS)   // Evict if not accessed for 2 hours
            .build();

    /**
     * Get closing prices for ALL stocks on anchor date.
     * Uses L1 (Caffeine) → L2 (DB) fallback.
     *
     * @param date The anchor date
     * @return Map of ticker → closing price on that date
     */
    public Map<String, Double> getPricesForDate(LocalDate date) {
        if (date == null) {
            return Collections.emptyMap();
        }

        // Don't allow future dates
        if (date.isAfter(LocalDate.now())) {
            log.warn("Anchor date {} is in the future, returning empty", date);
            return Collections.emptyMap();
        }

        long start = System.currentTimeMillis();

        // 1. Check L1 (Caffeine) - instant
        Map<String, Double> l1Cached = l1Cache.getIfPresent(date);
        if (l1Cached != null && !l1Cached.isEmpty()) {
            log.debug("L1 cache HIT for {} - {} tickers in {}ms",
                    date, l1Cached.size(), System.currentTimeMillis() - start);
            return l1Cached;
        }

        // 2. Load from L2 (Database)
        List<AnchorPriceCache> dbResults = repository.findByIdPriceDate(date);

        if (dbResults.isEmpty()) {
            log.warn("No prices found in DB for {} - run EOD job or backfill first", date);
            return Collections.emptyMap();
        }

        Map<String, Double> result = new HashMap<>();
        for (AnchorPriceCache entry : dbResults) {
            if (entry.getClosePrice() != null && entry.getClosePrice() > 0) {
                result.put(entry.getTicker(), entry.getClosePrice());
            }
        }

        // 3. Update L1 cache
        l1Cache.put(date, result);

        log.info("Anchor prices for {}: loaded {} prices from DB in {}ms",
                date, result.size(), System.currentTimeMillis() - start);

        return result;
    }

    /**
     * Get closing prices for specific tickers on anchor date.
     * Filters from the full date cache.
     *
     * @param date    The anchor date
     * @param tickers List of stock tickers (e.g., ["TCS", "RELIANCE", "INFY"])
     * @return Map of ticker → closing price on that date
     */
    public Map<String, Double> getPricesForDate(LocalDate date, List<String> tickers) {
        if (date == null || tickers == null || tickers.isEmpty()) {
            return Collections.emptyMap();
        }

        // Normalize tickers (uppercase, remove NSE: prefix)
        Set<String> normalizedTickers = tickers.stream()
                .map(t -> t.toUpperCase().replace("NSE:", "").trim())
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toSet());

        if (normalizedTickers.isEmpty()) {
            return Collections.emptyMap();
        }

        // Get all prices for the date
        Map<String, Double> allPrices = getPricesForDate(date);

        // Filter by requested tickers
        Map<String, Double> result = new HashMap<>();
        for (String ticker : normalizedTickers) {
            Double price = allPrices.get(ticker);
            if (price != null) {
                result.put(ticker, price);
            }
        }

        return result;
    }

    /**
     * Warm L1 cache with all prices for a popular date.
     * Call this for frequently accessed dates (Budget, RBI Policy, etc.)
     */
    public void warmCache(LocalDate date) {
        log.info("Warming L1 cache for {}", date);
        Map<String, Double> prices = getPricesForDate(date);
        log.info("Warmed L1 cache with {} prices for {}", prices.size(), date);
    }

    /**
     * Get cache statistics for monitoring.
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();

        // L1 stats
        stats.put("l1CacheSize", l1Cache.estimatedSize());
        stats.put("l1CachedDates", l1Cache.asMap().keySet());

        // L2 stats
        List<LocalDate> cachedDates = repository.findAllCachedDates();
        stats.put("l2CachedDates", cachedDates);
        stats.put("l2TotalRecords", repository.count());

        return stats;
    }

    /**
     * Check if prices exist for a date.
     */
    public boolean hasPricesForDate(LocalDate date) {
        // Check L1 first
        Map<String, Double> l1Cached = l1Cache.getIfPresent(date);
        if (l1Cached != null && !l1Cached.isEmpty()) {
            return true;
        }
        // Check L2
        return repository.countByDate(date) > 0;
    }

    /**
     * Get count of prices for a date.
     */
    public long getPriceCountForDate(LocalDate date) {
        return repository.countByDate(date);
    }

    /**
     * Find the nearest cached date on or before the given date.
     * Useful when the requested date is a holiday/weekend with no trading data.
     *
     * @param date The requested date
     * @return The nearest date with data, or null if none found
     */
    public LocalDate findNearestDate(LocalDate date) {
        if (date == null) return null;
        return repository.findNearestDateOnOrBefore(date);
    }

    /**
     * Get all dates that have cached price data.
     */
    public List<LocalDate> getAvailableDates() {
        return repository.findAllCachedDates();
    }
}
