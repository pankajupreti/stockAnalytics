package com.example.portfolio_service.controller;

import com.example.portfolio_service.client.AnnouncementClient;
import com.example.portfolio_service.model.Position;
import com.example.portfolio_service.security.CurrentUser;
import com.example.portfolio_service.service.PortfolioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller for portfolio announcement integration.
 * Provides endpoints to check which portfolio stocks have corporate announcements.
 */
@RestController
@RequestMapping("/api/portfolio/announcements")
@RequiredArgsConstructor
@Slf4j
public class PortfolioAnnouncementController {

    private final PortfolioService portfolioService;
    private final AnnouncementClient announcementClient;
    private final CurrentUser currentUser;

    /**
     * Check which stocks in the user's portfolio have recent announcements.
     * Returns a map of ticker -> hasAnnouncement
     *
     * GET /api/portfolio/announcements/check?days=7
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Boolean>> checkPortfolioAnnouncements(
            Authentication auth,
            @RequestParam(defaultValue = "7") int days
    ) {
        String userSub = currentUser.sub(auth);
        String token = currentUser.token(auth);

        // Get user's portfolio tickers
        List<String> tickers = portfolioService.list(userSub).stream()
                .map(Position::getTicker)
                .distinct()
                .toList();

        if (tickers.isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }

        log.debug("Checking announcements for portfolio tickers: {}", tickers);

        Map<String, Boolean> result = announcementClient.checkTickersForAnnouncements(tickers, days, token)
                .blockOptional()
                .orElse(Map.of());

        return ResponseEntity.ok(result);
    }

    /**
     * Get announcements for stocks in the user's portfolio.
     *
     * GET /api/portfolio/announcements?days=7
     */
    @GetMapping
    public ResponseEntity<List<AnnouncementClient.AnnouncementDTO>> getPortfolioAnnouncements(
            Authentication auth,
            @RequestParam(defaultValue = "7") int days
    ) {
        String userSub = currentUser.sub(auth);
        String token = currentUser.token(auth);

        // Get user's portfolio tickers
        List<String> tickers = portfolioService.list(userSub).stream()
                .map(Position::getTicker)
                .distinct()
                .toList();

        if (tickers.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        log.debug("Fetching announcements for portfolio tickers: {}", tickers);

        List<AnnouncementClient.AnnouncementDTO> announcements = announcementClient
                .getAnnouncementsByTickers(tickers, days, token)
                .blockOptional()
                .orElse(List.of());

        return ResponseEntity.ok(announcements);
    }

    /**
     * Get announcements grouped by ticker for portfolio view.
     *
     * GET /api/portfolio/announcements/grouped?days=7&maxPerTicker=5
     */
    @GetMapping("/grouped")
    public ResponseEntity<List<AnnouncementClient.PortfolioAnnouncementDTO>> getGroupedPortfolioAnnouncements(
            Authentication auth,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "5") int maxPerTicker
    ) {
        String token = currentUser.token(auth);

        log.debug("Fetching grouped portfolio announcements");

        List<AnnouncementClient.PortfolioAnnouncementDTO> announcements = announcementClient
                .getPortfolioAnnouncements(days, maxPerTicker, token)
                .blockOptional()
                .orElse(List.of());

        return ResponseEntity.ok(announcements);
    }

    /**
     * Get count of stocks with announcements in user's portfolio.
     * Useful for showing a notification badge.
     *
     * GET /api/portfolio/announcements/count?days=7
     */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> getAnnouncementCount(
            Authentication auth,
            @RequestParam(defaultValue = "7") int days
    ) {
        String userSub = currentUser.sub(auth);
        String token = currentUser.token(auth);

        // Get user's portfolio tickers
        List<String> tickers = portfolioService.list(userSub).stream()
                .map(Position::getTicker)
                .distinct()
                .toList();

        if (tickers.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "totalStocks", 0,
                    "stocksWithAnnouncements", 0,
                    "hasAnnouncements", false
            ));
        }

        Map<String, Boolean> checkResult = announcementClient.checkTickersForAnnouncements(tickers, days, token)
                .blockOptional()
                .orElse(Map.of());

        long stocksWithAnnouncements = checkResult.values().stream()
                .filter(Boolean::booleanValue)
                .count();

        return ResponseEntity.ok(Map.of(
                "totalStocks", tickers.size(),
                "stocksWithAnnouncements", stocksWithAnnouncements,
                "hasAnnouncements", stocksWithAnnouncements > 0
        ));
    }
}
