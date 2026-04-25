package com.example.portfolio_service.controller;

import com.example.portfolio_service.dto.PortfolioAnalyticsDTO;
import com.example.portfolio_service.security.CurrentUser;
import com.example.portfolio_service.service.PortfolioAnalyticsService;
import com.example.portfolio_service.service.PortfolioReturnsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
public class PortfolioAnalyticsController {

    private final PortfolioAnalyticsService analyticsService;
    private final PortfolioReturnsService returnsService;
    private final CurrentUser currentUser;

    /**
     * Get comprehensive portfolio analytics including:
     * - Summary stats (total invested, P&L)
     * - Sector diversification breakdown
     * - Top/bottom performers
     * - 52-week high/low analysis
     * - Risk metrics
     */
    @GetMapping("/analytics")
    public PortfolioAnalyticsDTO getAnalytics(Authentication auth, @AuthenticationPrincipal Jwt jwt) {
        return analyticsService.getAnalytics(currentUser.sub(auth), jwt.getTokenValue());
    }

    /**
     * One-time migration endpoint to fix historical snapshots.
     * Recalculates totalWealth and normalizedValue using the correct formula.
     * POST /api/portfolio/admin/fix-snapshots
     */
    @PostMapping("/admin/fix-snapshots")
    public ResponseEntity<Map<String, Object>> fixHistoricalSnapshots() {
        int fixed = returnsService.recalculateHistoricalSnapshots();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Historical snapshots recalculated",
                "snapshotsFixed", fixed
        ));
    }
}
