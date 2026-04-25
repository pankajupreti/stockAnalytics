package com.example.reporting.controller;

import com.example.reporting.service.AnchorMoveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Controller for anchor move functionality.
 * Provides historical prices for calculating stock movement since anchor dates.
 *
 * NOTE: This controller only READS data from the database.
 * Use the EOD Price APIs in sheet-import-service to populate data.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class AnchorMoveController {

    private final AnchorMoveService anchorMoveService;

    /**
     * Get historical closing prices for ALL stocks on anchor date.
     *
     * GET /api/anchor-prices?date=2026-02-01
     *
     * @param date The anchor date (historical date to get prices for)
     * @return Map of ticker → closing price on that date
     */
    @GetMapping("/anchor-prices")
    public Map<String, Double> getAnchorPrices(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        log.info("Anchor prices request: date={}", date);

        Map<String, Double> prices = anchorMoveService.getPricesForDate(date);

        if (prices.isEmpty()) {
            log.warn("No prices found for {} - ensure EOD job has run or backfill data", date);
        }

        return prices;
    }

    /**
     * Check if prices exist for a date.
     *
     * GET /api/anchor-prices/exists?date=2026-02-01
     */
    @GetMapping("/anchor-prices/exists")
    public Map<String, Object> checkPricesExist(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        boolean exists = anchorMoveService.hasPricesForDate(date);
        long count = exists ? anchorMoveService.getPriceCountForDate(date) : 0;

        return Map.of(
                "date", date.toString(),
                "exists", exists,
                "count", count
        );
    }

    /**
     * Get list of popular anchor dates (significant market events).
     * Frontend can use this to populate a dropdown.
     *
     * GET /api/anchor-events
     */
    @GetMapping("/anchor-events")
    public List<Map<String, String>> getAnchorEvents() {
        // Hardcoded for now - could be moved to database later
        return List.of(
                Map.of("date", "2026-02-01", "name", "Budget 2026", "category", "economic"),
                Map.of("date", "2026-02-07", "name", "RBI Policy Feb 2026", "category", "monetary"),
                Map.of("date", "2026-01-20", "name", "Trump Inauguration", "category", "global"),
                Map.of("date", "2025-06-04", "name", "Election Results 2024", "category", "political"),
                Map.of("date", "2025-02-01", "name", "Budget 2025", "category", "economic"),
                Map.of("date", "2024-06-04", "name", "Election Results 2024", "category", "political")
        );
    }

    /**
     * Get list of dates that have cached price data.
     *
     * GET /api/anchor-prices/available-dates
     */
    @GetMapping("/anchor-prices/available-dates")
    public List<String> getAvailableDates() {
        return anchorMoveService.getAvailableDates().stream()
                .map(LocalDate::toString)
                .toList();
    }

    /**
     * Warm the cache for a popular date.
     * Call this proactively for dates you expect high traffic.
     *
     * POST /api/anchor-prices/warm?date=2026-02-01
     */
    @PostMapping("/anchor-prices/warm")
    public Map<String, Object> warmCache(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        log.info("Warming cache for {}", date);
        anchorMoveService.warmCache(date);

        return Map.of(
                "status", "success",
                "date", date.toString(),
                "message", "Cache warmed successfully"
        );
    }

    /**
     * Get cache statistics for monitoring.
     *
     * GET /api/anchor-prices/stats
     */
    @GetMapping("/anchor-prices/stats")
    public Map<String, Object> getCacheStats() {
        return anchorMoveService.getCacheStats();
    }
}
