package com.example.announcement_service.controller;

import com.example.announcement_service.dto.PeadScannerResponse;
import com.example.announcement_service.service.PeadScannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST Controller for PEAD (Post Earnings Announcement Drift) Scanner.
 *
 * PEAD is a market phenomenon where stocks tend to continue drifting
 * in the direction of their earnings surprise for weeks/months after
 * the announcement.
 *
 * This scanner helps identify:
 * - Stocks with strong quarterly results (PAT/Revenue growth)
 * - Their price performance since results announcement
 * - Distance from 52W high/low
 * - Basic relative strength rating
 */
@RestController
@RequestMapping("/api/pead")
@RequiredArgsConstructor
@Slf4j
public class PeadScannerController {

    private final PeadScannerService peadScannerService;

    /**
     * Run the PEAD scanner with optional filters.
     *
     * Example: GET /api/pead/scan?minPatYoY=15&minRevenueYoY=10&currentQuarterOnly=true
     *
     * @param minRevenueYoY Minimum Revenue YoY growth % (default: 10)
     * @param minRevenueQoQ Minimum Revenue QoQ growth %
     * @param minPatYoY Minimum PAT YoY growth % (default: 15)
     * @param minPatQoQ Minimum PAT QoQ growth %
     * @param minPbtYoY Minimum PBT YoY growth %
     * @param minPbtQoQ Minimum PBT QoQ growth %
     * @param minMarketCap Minimum market cap in Crores
     * @param maxMarketCap Maximum market cap in Crores
     * @param minPctChange Minimum % change since results announcement
     * @param currentQuarterOnly Filter to current quarter results only (default: true)
     * @param quarter Specific quarter (Q1/Q2/Q3/Q4)
     * @param fiscalYear Specific fiscal year (e.g., 2026)
     * @param resultType Result type: "consolidated" or "standalone" (default: consolidated)
     * @param sortBy Sort by: "pctChangeSinceResults", "patYoY", "revenueYoY", "pbtYoY", "announcementDate", "marketCap", "rsRating" (default: pctChangeSinceResults)
     * @param sortOrder Sort order: "desc" or "asc" (default: desc)
     * @param limit Maximum results to return (default: 100)
     * @return PeadScannerResponse with matching stocks
     */
    @GetMapping("/scan")
    public ResponseEntity<PeadScannerResponse> scan(
            @RequestParam(required = false) Double minRevenueYoY,
            @RequestParam(required = false) Double minRevenueQoQ,
            @RequestParam(required = false) Double minPatYoY,
            @RequestParam(required = false) Double minPatQoQ,
            @RequestParam(required = false) Double minPbtYoY,
            @RequestParam(required = false) Double minPbtQoQ,
            @RequestParam(required = false) Double minMarketCap,
            @RequestParam(required = false) Double maxMarketCap,
            @RequestParam(required = false) Double minPctChange,
            @RequestParam(required = false, defaultValue = "true") boolean currentQuarterOnly,
            @RequestParam(required = false) String quarter,
            @RequestParam(required = false) Integer fiscalYear,
            @RequestParam(required = false, defaultValue = "consolidated") String resultType,
            @RequestParam(required = false, defaultValue = "pctChangeSinceResults") String sortBy,
            @RequestParam(required = false, defaultValue = "desc") String sortOrder,
            @RequestParam(required = false, defaultValue = "100") int limit
    ) {
        log.info("PEAD scan request: patYoY>={}, revYoY>={}, pbtYoY>={}, currentQtr={}, mcap={}..{}, sortBy={}",
                minPatYoY, minRevenueYoY, minPbtYoY, currentQuarterOnly, minMarketCap, maxMarketCap, sortBy);

        try {
            PeadScannerResponse response = peadScannerService.scan(
                    minRevenueYoY,
                    minRevenueQoQ,
                    minPatYoY,
                    minPatQoQ,
                    minPbtYoY,
                    minPbtQoQ,
                    minMarketCap,
                    maxMarketCap,
                    minPctChange,
                    currentQuarterOnly,
                    quarter,
                    fiscalYear,
                    resultType,
                    sortBy,
                    sortOrder,
                    limit
            );

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("PEAD scan failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Get the current quarter info based on Indian fiscal year.
     * Useful for understanding which quarter's results are being announced.
     *
     * @return Map with "quarter" and "fiscalYear"
     */
    @GetMapping("/current-quarter")
    public ResponseEntity<Map<String, Object>> getCurrentQuarter() {
        return ResponseEntity.ok(peadScannerService.getCurrentQuarterInfo());
    }

    /**
     * Quick scan with sensible defaults - finds stocks with strong results
     * that are showing positive drift.
     *
     * Default filters:
     * - PAT YoY >= 20%
     * - Revenue YoY >= 15%
     * - Market Cap >= 500 Cr
     * - Current quarter only
     * - Sorted by % change since results
     */
    @GetMapping("/scan/quick")
    public ResponseEntity<PeadScannerResponse> quickScan(
            @RequestParam(required = false, defaultValue = "50") int limit
    ) {
        log.info("Quick PEAD scan requested");

        PeadScannerResponse response = peadScannerService.scan(
                15.0,   // minRevenueYoY
                null,   // minRevenueQoQ
                20.0,   // minPatYoY
                null,   // minPatQoQ
                null,   // minPbtYoY
                null,   // minPbtQoQ
                500.0,  // minMarketCap
                null,   // maxMarketCap
                null,   // minPctChange
                true,   // currentQuarterOnly
                null,   // quarter
                null,   // fiscalYear
                "consolidated",
                "pctChangeSinceResults",
                "desc",
                limit
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Scan for potential turnaround stories - stocks that had good results
     * but haven't moved much yet (potential PEAD opportunity).
     *
     * Looks for:
     * - Strong results (PAT YoY >= 25%)
     * - Still trading below 52W high (pctFrom52WeekHigh > -15%)
     * - Current quarter
     */
    @GetMapping("/scan/turnarounds")
    public ResponseEntity<PeadScannerResponse> turnaroundScan(
            @RequestParam(required = false, defaultValue = "50") int limit
    ) {
        log.info("Turnaround PEAD scan requested");

        // Get stocks with strong results but limited price move
        PeadScannerResponse response = peadScannerService.scan(
                10.0,   // minRevenueYoY
                null,   // minRevenueQoQ
                25.0,   // minPatYoY - strong profit growth
                null,   // minPatQoQ
                null,   // minPbtYoY
                null,   // minPbtQoQ
                100.0,  // minMarketCap - include smaller caps
                null,   // maxMarketCap
                null,   // minPctChange - we'll filter for lower moves
                true,   // currentQuarterOnly
                null,   // quarter
                null,   // fiscalYear
                "consolidated",
                "patYoY",  // Sort by profit growth
                "desc",
                limit
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Scan for momentum leaders - stocks with strong results AND strong price action.
     *
     * Looks for:
     * - Strong results (PAT YoY >= 20%, Revenue YoY >= 15%)
     * - Near 52W high (within 10%)
     * - Higher market cap (>= 1000 Cr)
     */
    @GetMapping("/scan/momentum")
    public ResponseEntity<PeadScannerResponse> momentumScan(
            @RequestParam(required = false, defaultValue = "50") int limit
    ) {
        log.info("Momentum PEAD scan requested");

        PeadScannerResponse response = peadScannerService.scan(
                15.0,    // minRevenueYoY
                null,    // minRevenueQoQ
                20.0,    // minPatYoY
                null,    // minPatQoQ
                null,    // minPbtYoY
                null,    // minPbtQoQ
                1000.0,  // minMarketCap - mid/large caps only
                null,    // maxMarketCap
                5.0,     // minPctChange - showing positive drift
                true,    // currentQuarterOnly
                null,    // quarter
                null,    // fiscalYear
                "consolidated",
                "rsRating",  // Sort by relative strength
                "desc",
                limit
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Scan for small-cap gems - smaller stocks with explosive growth.
     *
     * Looks for:
     * - Very strong results (PAT YoY >= 30%)
     * - Smaller market cap (100-2000 Cr)
     */
    @GetMapping("/scan/smallcap")
    public ResponseEntity<PeadScannerResponse> smallCapScan(
            @RequestParam(required = false, defaultValue = "50") int limit
    ) {
        log.info("SmallCap PEAD scan requested");

        PeadScannerResponse response = peadScannerService.scan(
                10.0,    // minRevenueYoY
                null,    // minRevenueQoQ
                30.0,    // minPatYoY - explosive growth
                null,    // minPatQoQ
                null,    // minPbtYoY
                null,    // minPbtQoQ
                100.0,   // minMarketCap
                2000.0,  // maxMarketCap - small cap range
                null,    // minPctChange
                true,    // currentQuarterOnly
                null,    // quarter
                null,    // fiscalYear
                "consolidated",
                "patYoY",
                "desc",
                limit
        );

        return ResponseEntity.ok(response);
    }
}
