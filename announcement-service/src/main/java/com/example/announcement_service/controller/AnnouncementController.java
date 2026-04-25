package com.example.announcement_service.controller;

import com.example.announcement_service.dto.AnnouncementDTO;
import com.example.announcement_service.dto.PortfolioAnnouncementDTO;
import com.example.announcement_service.security.CurrentUser;
import com.example.announcement_service.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
@Slf4j
public class AnnouncementController {

    private final AnnouncementService announcementService;
    private final CurrentUser currentUser;

    /**
     * Get all announcements with optional filters
     * GET /api/announcements?category=Result&fromDate=2024-01-01&toDate=2024-01-31&page=0&size=20
     */
    @GetMapping
    public ResponseEntity<Page<AnnouncementDTO>> getAnnouncements(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        log.debug("Fetching announcements - category: {}, from: {}, to: {}, page: {}", category, fromDate, toDate, page);
        Page<AnnouncementDTO> announcements = announcementService.getAnnouncements(category, fromDate, toDate, page, size);
        return ResponseEntity.ok(announcements);
    }

    /**
     * Get announcements for specific tickers
     * GET /api/announcements/by-tickers?tickers=RELIANCE,TCS,INFY&days=7
     */
    @GetMapping("/by-tickers")
    public ResponseEntity<List<AnnouncementDTO>> getAnnouncementsByTickers(
            @RequestParam List<String> tickers,
            @RequestParam(defaultValue = "7") int days
    ) {
        log.debug("Fetching announcements for tickers: {}, last {} days", tickers, days);
        List<AnnouncementDTO> announcements = announcementService.getAnnouncementsByTickers(tickers, days);
        return ResponseEntity.ok(announcements);
    }

    /**
     * Get announcements for user's portfolio stocks
     * GET /api/announcements/portfolio?days=7&maxPerTicker=5
     */
    @GetMapping("/portfolio")
    public ResponseEntity<List<PortfolioAnnouncementDTO>> getPortfolioAnnouncements(
            Authentication auth,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "5") int maxPerTicker
    ) {
        String token = currentUser.token(auth);
        log.debug("Fetching portfolio announcements for user, last {} days", days);
        List<PortfolioAnnouncementDTO> announcements = announcementService.getPortfolioAnnouncements(token, days, maxPerTicker);
        return ResponseEntity.ok(announcements);
    }

    /**
     * Check which tickers from a list have recent announcements
     * GET /api/announcements/check?tickers=RELIANCE,TCS,INFY&days=7
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Boolean>> checkTickersForAnnouncements(
            @RequestParam List<String> tickers,
            @RequestParam(defaultValue = "7") int days
    ) {
        log.debug("Checking announcements for tickers: {}", tickers);
        Map<String, Boolean> result = announcementService.checkTickersForAnnouncements(tickers, days);
        return ResponseEntity.ok(result);
    }

    /**
     * Get announcement counts for specific tickers.
     * Returns a map of ticker -> announcement count for the specified period.
     * GET /api/announcements/counts?tickers=RELIANCE,TCS,INFY&days=7
     */
    @GetMapping("/counts")
    public ResponseEntity<Map<String, Integer>> getAnnouncementCounts(
            @RequestParam List<String> tickers,
            @RequestParam(defaultValue = "7") int days
    ) {
        log.debug("Getting announcement counts for tickers: {}", tickers);
        Map<String, Integer> counts = announcementService.getAnnouncementCounts(tickers, days);
        return ResponseEntity.ok(counts);
    }

    /**
     * Get a specific announcement by ID
     * GET /api/announcements/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<AnnouncementDTO> getAnnouncementById(@PathVariable Long id) {
        return announcementService.getAnnouncementById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get list of available categories
     * GET /api/announcements/categories
     */
    @GetMapping("/categories")
    public ResponseEntity<List<String>> getCategories() {
        List<String> categories = announcementService.getCategories();
        return ResponseEntity.ok(categories);
    }

    /**
     * Search for companies/tickers (autocomplete).
     * Returns matching companies from announcements for search suggestions.
     * GET /api/announcements/search?q=tejas
     */
    @GetMapping("/search")
    public ResponseEntity<List<Map<String, String>>> searchCompanies(
            @RequestParam("q") String query) {
        if (query == null || query.trim().length() < 2) {
            return ResponseEntity.ok(List.of());
        }

        log.debug("Searching companies for: {}", query);
        List<Map<String, String>> results = announcementService.searchCompanies(query.trim());
        return ResponseEntity.ok(results);
    }

    /**
     * Manually trigger sync from BSE API (admin only - for testing)
     * POST /api/announcements/sync?fromDate=2024-01-01&toDate=2024-01-31
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> triggerSync(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String category
    ) {
        LocalDate from = fromDate != null ? fromDate : LocalDate.now().minusDays(1);
        LocalDate to = toDate != null ? toDate : LocalDate.now();

        log.info("Manual sync triggered from {} to {}", from, to);
        int count = announcementService.syncAnnouncements(from, to, category);

        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "savedCount", count,
                "fromDate", from.toString(),
                "toDate", to.toString()
        ));
    }

    // ==================== SEEN/UNSEEN STATUS ENDPOINTS ====================

    /**
     * Mark announcements as seen by the user.
     * POST /api/announcements/seen
     * Body: { "newsIds": ["newsId1", "newsId2"] }
     */
    @PostMapping("/seen")
    public ResponseEntity<Map<String, Object>> markAnnouncementsAsSeen(
            Authentication auth,
            @RequestBody Map<String, List<String>> body
    ) {
        String userId = currentUser.userId(auth);
        List<String> newsIds = body.get("newsIds");

        log.debug("Marking announcements as seen for user {}: {}", userId, newsIds);
        int count = announcementService.markAnnouncementsAsSeen(userId, newsIds);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "markedCount", count
        ));
    }

    /**
     * Mark announcements as seen by IDs.
     * POST /api/announcements/seen-by-ids
     * Body: { "ids": [1, 2, 3] }
     */
    @PostMapping("/seen-by-ids")
    public ResponseEntity<Map<String, Object>> markAnnouncementsAsSeenByIds(
            Authentication auth,
            @RequestBody Map<String, List<Long>> body
    ) {
        String userId = currentUser.userId(auth);
        List<Long> ids = body.get("ids");

        log.debug("Marking announcements as seen by IDs for user {}: {}", userId, ids);
        int count = announcementService.markAnnouncementsAsSeenByIds(userId, ids);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "markedCount", count
        ));
    }

    /**
     * Get unseen announcement counts per ticker.
     * GET /api/announcements/unseen-counts?tickers=NSE:RELIANCE,NSE:TCS&days=7
     */
    @GetMapping("/unseen-counts")
    public ResponseEntity<Map<String, Integer>> getUnseenCounts(
            Authentication auth,
            @RequestParam List<String> tickers,
            @RequestParam(defaultValue = "7") int days
    ) {
        String userId = currentUser.userId(auth);
        log.debug("Getting unseen announcement counts for user {}, tickers: {}", userId, tickers);
        Map<String, Integer> counts = announcementService.getUnseenCounts(userId, tickers, days);
        return ResponseEntity.ok(counts);
    }

    /**
     * Get total unseen announcement count for portfolio.
     * GET /api/announcements/unseen-count?tickers=NSE:RELIANCE,NSE:TCS&days=7
     */
    @GetMapping("/unseen-count")
    public ResponseEntity<Map<String, Object>> getUnseenCount(
            Authentication auth,
            @RequestParam List<String> tickers,
            @RequestParam(defaultValue = "7") int days
    ) {
        String userId = currentUser.userId(auth);
        log.debug("Getting total unseen count for user {}", userId);
        long count = announcementService.getUnseenCount(userId, tickers, days);
        return ResponseEntity.ok(Map.of("unseenCount", count));
    }

    /**
     * Mark all announcements for given tickers as seen.
     * POST /api/announcements/mark-all-seen
     * Body: { "tickers": ["NSE:RELIANCE", "NSE:TCS"], "days": 7 }
     */
    @PostMapping("/mark-all-seen")
    public ResponseEntity<Map<String, Object>> markAllAsSeen(
            Authentication auth,
            @RequestBody Map<String, Object> body
    ) {
        String userId = currentUser.userId(auth);
        @SuppressWarnings("unchecked")
        List<String> tickers = (List<String>) body.get("tickers");
        int days = body.get("days") != null ? ((Number) body.get("days")).intValue() : 7;

        log.debug("Marking all announcements as seen for user {}, tickers: {}", userId, tickers);
        int count = announcementService.markAllAsSeenForTickers(userId, tickers, days);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "markedCount", count
        ));
    }
}
