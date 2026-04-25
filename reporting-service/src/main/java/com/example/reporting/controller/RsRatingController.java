package com.example.reporting.controller;

import com.example.reporting.service.RsRatingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for RS Rating operations.
 * Provides endpoints to manually trigger RS Rating calculation.
 */
@RestController
@RequestMapping("/api/rs-rating")
public class RsRatingController {

    private final RsRatingService rsRatingService;

    public RsRatingController(RsRatingService rsRatingService) {
        this.rsRatingService = rsRatingService;
    }

    /**
     * Manually trigger RS Rating recalculation for all stocks.
     * POST /api/rs-rating/calculate
     */
    @PostMapping("/calculate")
    public ResponseEntity<Map<String, Object>> calculateRsRatings() {
        long startTime = System.currentTimeMillis();
        int updatedCount = rsRatingService.calculateAndUpdateRsRatings();
        long duration = System.currentTimeMillis() - startTime;

        return ResponseEntity.ok(Map.of(
                "success", true,
                "updatedCount", updatedCount,
                "durationMs", duration,
                "message", String.format("RS Rating calculated for %d stocks in %d ms", updatedCount, duration)
        ));
    }

    /**
     * Get RS Rating for a specific ticker.
     * GET /api/rs-rating/{ticker}
     */
    @GetMapping("/{ticker}")
    public ResponseEntity<Map<String, Object>> getRsRating(@PathVariable String ticker) {
        Double rsRating = rsRatingService.getRsRating(ticker);

        if (rsRating != null) {
            return ResponseEntity.ok(Map.of(
                    "ticker", ticker,
                    "rsRating", rsRating
            ));
        } else {
            return ResponseEntity.ok(Map.of(
                    "ticker", ticker,
                    "rsRating", (Object) null,
                    "message", "RS Rating not calculated for this ticker. Try calling /api/rs-rating/calculate first."
            ));
        }
    }
}
