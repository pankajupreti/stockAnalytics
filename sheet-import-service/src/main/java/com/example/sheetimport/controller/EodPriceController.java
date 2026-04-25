package com.example.sheetimport.controller;

import com.example.sheetimport.service.EodPriceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/**
 * REST API for EOD price operations.
 * Used for backfilling historical prices and prefilling event dates.
 */
@RestController
@RequestMapping("/api/eod-prices")
public class EodPriceController {

    private static final Logger log = LoggerFactory.getLogger(EodPriceController.class);

    private final EodPriceService eodPriceService;

    public EodPriceController(EodPriceService eodPriceService) {
        this.eodPriceService = eodPriceService;
    }

    /**
     * Prefill prices for a specific date.
     * Use for event dates like Budget, RBI Policy, etc.
     *
     * POST /api/eod-prices/prefill?date=2026-02-01
     * POST /api/eod-prices/prefill?date=2026-02-01&force=true (re-fetch even if exists)
     */
    @PostMapping("/prefill")
    public Map<String, Object> prefillDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean force) {

        log.info("Prefill request for date={}, force={}", date, force);
        return eodPriceService.fetchPricesForDate(date, force);
    }

    /**
     * Backfill historical prices for a date range.
     * Runs in background - returns immediately.
     *
     * POST /api/eod-prices/backfill-historical?fromDate=2025-01-01&toDate=2026-02-15
     */
    @PostMapping("/backfill-historical")
    public Map<String, Object> backfillHistorical(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        log.info("Backfill historical request from {} to {}", fromDate, toDate);
        return eodPriceService.backfillHistorical(fromDate, toDate);
    }

    /**
     * Get status of current operation.
     *
     * GET /api/eod-prices/status
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        return eodPriceService.getStatus();
    }

    /**
     * Prefill prices async - returns immediately and runs in background.
     * Frontend polls /api/eod-prices/status for progress.
     *
     * POST /api/eod-prices/prefill-async?date=2026-03-31
     */
    @PostMapping("/prefill-async")
    public Map<String, Object> prefillAsync(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean force) {

        log.info("Async prefill request for date={}, force={}", date, force);
        return eodPriceService.fetchPricesForDateAsync(date, force);
    }

    /**
     * Prefill today's prices (for testing).
     *
     * POST /api/eod-prices/prefill-today
     */
    @PostMapping("/prefill-today")
    public Map<String, Object> prefillToday() {
        LocalDate today = LocalDate.now();
        log.info("Prefill today request for {}", today);
        return eodPriceService.fetchPricesForDate(today, false);
    }

    /**
     * Re-fetch all cached anchor dates with force=true.
     * Fixes stale prices caused by stock splits/bonuses after the original fetch.
     * Yahoo Finance returns split-adjusted prices when queried retroactively.
     *
     * POST /api/eod-prices/refresh-all
     */
    @PostMapping("/refresh-all")
    public Map<String, Object> refreshAllDates() {
        log.info("Refresh-all request: re-fetching all cached anchor dates with split-adjusted prices");
        return eodPriceService.refreshAllCachedDates();
    }

    /**
     * Cancel ongoing operation.
     *
     * POST /api/eod-prices/cancel
     */
    @PostMapping("/cancel")
    public Map<String, Object> cancel() {
        log.info("Cancel request received");
        eodPriceService.cancel();
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("status", "cancelled");
        result.put("message", "Operation cancelled. Note: already submitted tasks will complete.");
        return result;
    }
}
