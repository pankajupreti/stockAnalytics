package com.example.announcement_service.controller;

import com.example.announcement_service.ai.AnnouncementAiService;
import com.example.announcement_service.dto.AnnouncementDTO;
import com.example.announcement_service.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Test controller for development/testing without authentication.
 * These endpoints are publicly accessible when security.test-mode=true
 */
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Slf4j
public class TestController {

    private final AnnouncementService announcementService;
    private final com.example.announcement_service.service.TickerMappingService tickerMappingService;
    private final AnnouncementAiService aiService;
    private final com.example.announcement_service.service.ResultsEventPublisher resultsEventPublisher;
    private final com.example.announcement_service.repository.AnnouncementRepository announcementRepository;
    private final com.example.announcement_service.service.PricePrefetchService pricePrefetchService;
    private final com.example.announcement_service.repository.AnnouncementPriceCacheRepository priceCacheRepository;

    /**
     * Health check
     */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service", "announcement-service",
                "message", "Test endpoint working!"
        ));
    }

    /**
     * Test BSE sync - fetch announcements from BSE API
     * POST /api/test/sync?fromDate=2024-12-01&toDate=2024-12-13
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> testSync(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String category
    ) {
        LocalDate from = fromDate != null ? fromDate : LocalDate.now().minusDays(1);
        LocalDate to = toDate != null ? toDate : LocalDate.now();

        log.info("Test sync from {} to {}, category: {}", from, to, category);
        int count = announcementService.syncAnnouncements(from, to, category);

        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "savedCount", count,
                "fromDate", from.toString(),
                "toDate", to.toString()
        ));
    }

    /**
     * Test get announcements
     * GET /api/test/announcements?page=0&size=10
     */
    @GetMapping("/announcements")
    public ResponseEntity<Page<AnnouncementDTO>> testGetAnnouncements(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        log.info("Test get announcements - category: {}, from: {}, to: {}", category, fromDate, toDate);
        return ResponseEntity.ok(announcementService.getAnnouncements(category, fromDate, toDate, page, size));
    }

    /**
     * Test get announcements by tickers (from local DB only)
     * GET /api/test/announcements/by-tickers?tickers=RELIANCE,TCS&days=7
     */
    @GetMapping("/announcements/by-tickers")
    public ResponseEntity<List<AnnouncementDTO>> testGetByTickers(
            @RequestParam List<String> tickers,
            @RequestParam(defaultValue = "7") int days
    ) {
        log.info("Test get announcements for tickers: {}", tickers);
        return ResponseEntity.ok(announcementService.getAnnouncementsByTickers(tickers, days));
    }

    /**
     * LIVE fetch announcements from BSE API for a specific ticker.
     * This hits BSE in real-time, saves to DB, and returns fresh results.
     * GET /api/test/announcements/live?ticker=TCS&days=30
     */
    @GetMapping("/announcements/live")
    public ResponseEntity<List<AnnouncementDTO>> liveGetByTicker(
            @RequestParam String ticker,
            @RequestParam(defaultValue = "30") int days
    ) {
        log.info("LIVE fetch announcements for ticker: {}, days: {}", ticker, days);
        return ResponseEntity.ok(announcementService.fetchLiveAnnouncements(ticker, days));
    }

    /**
     * Test check tickers for announcements
     * GET /api/test/announcements/check?tickers=RELIANCE,TCS&days=7
     */
    @GetMapping("/announcements/check")
    public ResponseEntity<Map<String, Boolean>> testCheckTickers(
            @RequestParam List<String> tickers,
            @RequestParam(defaultValue = "7") int days
    ) {
        log.info("Test check tickers: {}", tickers);
        return ResponseEntity.ok(announcementService.checkTickersForAnnouncements(tickers, days));
    }

    /**
     * Get available categories
     * GET /api/test/categories
     */
    @GetMapping("/categories")
    public ResponseEntity<List<String>> testGetCategories() {
        return ResponseEntity.ok(announcementService.getCategories());
    }

    /**
     * Get ticker mappings (BSE scrip code -> NSE ticker)
     * GET /api/test/ticker-mappings
     */
    @GetMapping("/ticker-mappings")
    public ResponseEntity<Map<String, String>> testGetTickerMappings() {
        return ResponseEntity.ok(tickerMappingService.getAllMappings());
    }

    /**
     * Resolve a ticker format
     * GET /api/test/resolve-ticker?ticker=NSE:TCS
     * Returns the NSE ticker symbol from various formats
     */
    @GetMapping("/resolve-ticker")
    public ResponseEntity<Map<String, Object>> testResolveTicker(@RequestParam String ticker) {
        String resolved = tickerMappingService.extractNseTicker(ticker);
        return ResponseEntity.ok(Map.of(
                "input", ticker,
                "resolvedNseTicker", resolved != null ? resolved : "UNKNOWN",
                "hasMapping", resolved != null
        ));
    }

    /**
     * Update announcements with missing nseTicker mappings
     * POST /api/test/update-mappings
     */
    @PostMapping("/update-mappings")
    public ResponseEntity<Map<String, Object>> updateMissingMappings() {
        log.info("Manually triggered nseTicker mapping update");
        int updatedCount = announcementService.updateMissingNseTickers();
        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "updatedCount", updatedCount
        ));
    }

    /**
     * Manual sync trigger
     * POST /api/test/trigger-sync?days=1
     */
    @PostMapping("/trigger-sync")
    public ResponseEntity<Map<String, Object>> triggerSync(
            @RequestParam(defaultValue = "1") int days) {
        log.info("Manually triggered announcement sync for last {} days", days);
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate fromDate = today.minusDays(days);
        int savedCount = announcementService.syncAnnouncements(fromDate, today, null);
        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "savedCount", savedCount,
                "fromDate", fromDate.toString(),
                "toDate", today.toString()
        ));
    }

    /**
     * Get AI-generated summary for announcements of a ticker.
     * GET /api/test/announcements/ai-summary?ticker=TCS&days=30
     */
    @GetMapping("/announcements/ai-summary")
    public ResponseEntity<Map<String, Object>> getAiSummary(
            @RequestParam String ticker,
            @RequestParam(defaultValue = "30") int days
    ) {
        log.info("Generating AI summary for ticker: {}, days: {}", ticker, days);

        if (!aiService.isAvailable()) {
            return ResponseEntity.ok(Map.of(
                    "ticker", ticker,
                    "aiEnabled", false,
                    "summary", "AI summary is not available. Configure ai.openai.api-key and set ai.enabled=true.",
                    "announcementCount", 0
            ));
        }

        // Fetch announcements first
        List<AnnouncementDTO> announcements = announcementService.fetchLiveAnnouncements(ticker, days);

        if (announcements.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "ticker", ticker,
                    "aiEnabled", true,
                    "summary", "No announcements found for " + ticker + " in the last " + days + " days.",
                    "announcementCount", 0
            ));
        }

        // Generate AI summary
        String summary = aiService.summarizeAnnouncements(announcements, ticker);

        // Get relevance ranking
        Map<String, Integer> rankings = aiService.rankByRelevance(announcements);

        // Add rankings to announcements
        for (AnnouncementDTO ann : announcements) {
            if (rankings.containsKey(ann.getNewsId())) {
                ann.setAiSummary("Relevance: " + rankings.get(ann.getNewsId()) + "/10");
            }
        }

        return ResponseEntity.ok(Map.of(
                "ticker", ticker,
                "aiEnabled", true,
                "summary", summary != null ? summary : "Unable to generate summary.",
                "announcementCount", announcements.size(),
                "announcements", announcements,
                "rankings", rankings
        ));
    }

    /**
     * Check AI service availability
     * GET /api/test/ai-status
     */
    @GetMapping("/ai-status")
    public ResponseEntity<Map<String, Object>> getAiStatus() {
        return ResponseEntity.ok(Map.of(
                "aiEnabled", aiService.isAvailable(),
                "message", aiService.isAvailable() ?
                        "AI service is available" :
                        "AI service is not configured. Set ai.openai.api-key and ai.enabled=true"
        ));
    }

    /**
     * Autocomplete search for companies/tickers.
     * Returns matching companies from the database for search suggestions.
     * GET /api/test/companies/search?q=tejas
     */
    @GetMapping("/companies/search")
    public ResponseEntity<List<Map<String, String>>> searchCompanies(
            @RequestParam("q") String query) {
        if (query == null || query.trim().length() < 2) {
            return ResponseEntity.ok(List.of());
        }

        log.info("Searching companies for: {}", query);
        List<Map<String, String>> results = announcementService.searchCompanies(query.trim());
        return ResponseEntity.ok(results);
    }

    // ==================== SEEN/UNSEEN TEST ENDPOINTS ====================

    /**
     * Mark announcements as seen (test endpoint - uses hardcoded test user).
     * POST /api/test/announcements/seen
     * Body: { "newsIds": ["newsId1", "newsId2"] }
     */
    @PostMapping("/announcements/seen")
    public ResponseEntity<Map<String, Object>> testMarkAnnouncementsAsSeen(
            @RequestBody Map<String, List<String>> body
    ) {
        // Use a test user ID for unauthenticated testing
        String testUserId = "test-user";
        List<String> newsIds = body.get("newsIds");

        log.info("Test: Marking announcements as seen: {}", newsIds);
        int count = announcementService.markAnnouncementsAsSeen(testUserId, newsIds);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "markedCount", count,
                "userId", testUserId
        ));
    }

    /**
     * Get unseen announcement counts per ticker (test endpoint).
     * GET /api/test/announcements/unseen-counts?tickers=NSE:RELIANCE,NSE:TCS&days=7
     */
    @GetMapping("/announcements/unseen-counts")
    public ResponseEntity<Map<String, Integer>> testGetUnseenCounts(
            @RequestParam List<String> tickers,
            @RequestParam(defaultValue = "7") int days
    ) {
        String testUserId = "test-user";
        log.info("Test: Getting unseen counts for tickers: {}", tickers);
        Map<String, Integer> counts = announcementService.getUnseenCounts(testUserId, tickers, days);
        return ResponseEntity.ok(counts);
    }

    /**
     * Get total unseen announcement count (test endpoint).
     * GET /api/test/announcements/unseen-count?tickers=NSE:RELIANCE,NSE:TCS&days=7
     */
    @GetMapping("/announcements/unseen-count")
    public ResponseEntity<Map<String, Object>> testGetUnseenCount(
            @RequestParam List<String> tickers,
            @RequestParam(defaultValue = "7") int days
    ) {
        String testUserId = "test-user";
        log.info("Test: Getting total unseen count");
        long count = announcementService.getUnseenCount(testUserId, tickers, days);
        return ResponseEntity.ok(Map.of("unseenCount", count, "userId", testUserId));
    }

    /**
     * Mark all announcements for given tickers as seen (test endpoint).
     * POST /api/test/announcements/mark-all-seen
     * Body: { "tickers": ["NSE:RELIANCE", "NSE:TCS"], "days": 7 }
     */
    @PostMapping("/announcements/mark-all-seen")
    public ResponseEntity<Map<String, Object>> testMarkAllAsSeen(
            @RequestBody Map<String, Object> body
    ) {
        String testUserId = "test-user";
        @SuppressWarnings("unchecked")
        List<String> tickers = (List<String>) body.get("tickers");
        int days = body.get("days") != null ? ((Number) body.get("days")).intValue() : 7;

        log.info("Test: Marking all announcements as seen for tickers: {}", tickers);
        int count = announcementService.markAllAsSeenForTickers(testUserId, tickers, days);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "markedCount", count,
                "userId", testUserId
        ));
    }

    // ==================== RESULTS REPROCESS TEST ENDPOINTS ====================

    // Keywords that indicate a financial results announcement
    private static final List<String> FINANCIAL_RESULT_KEYWORDS = List.of(
            "financial result",
            "quarterly result",
            "un-audited financial",
            "unaudited financial",
            "audited financial",
            "standalone financial",
            "consolidated financial",
            "outcome of board meeting",
            "outcome of the board meeting",
            "board meeting outcome",
            "board meeting",
            "results for the quarter",
            "results for quarter"
    );

    /**
     * Reprocess ALL financial result announcements from the last N days.
     * Finds all announcements with category "Result" or matching keywords,
     * and republishes them to RabbitMQ.
     *
     * POST /api/test/results/reprocess-all?days=30
     */
    @PostMapping("/results/reprocess-all")
    public ResponseEntity<Map<String, Object>> testReprocessAllResults(
            @RequestParam(defaultValue = "30") int days
    ) {
        log.info("Reprocessing ALL financial result announcements from last {} days", days);

        LocalDateTime afterDate = LocalDateTime.now().minusDays(days);
        List<com.example.announcement_service.model.Announcement> allAnnouncements =
                announcementRepository.findByAnnouncementDateAfter(afterDate);

        // Filter for financial results with valid nseTicker
        List<com.example.announcement_service.model.Announcement> financialResults = allAnnouncements.stream()
                .filter(this::isFinancialResultAnnouncement)
                .filter(a -> a.getNseTicker() != null && !a.getNseTicker().isBlank())
                .toList();

        log.info("Found {} financial result announcements with valid tickers", financialResults.size());

        // Group by ticker and get latest announcement per ticker
        Map<String, com.example.announcement_service.model.Announcement> latestPerTicker = new LinkedHashMap<>();
        for (var ann : financialResults) {
            String ticker = ann.getNseTicker().toUpperCase();
            if (!latestPerTicker.containsKey(ticker) ||
                ann.getAnnouncementDate().isAfter(latestPerTicker.get(ticker).getAnnouncementDate())) {
                latestPerTicker.put(ticker, ann);
            }
        }

        List<String> publishedTickers = new ArrayList<>();
        List<String> failedTickers = new ArrayList<>();

        for (var entry : latestPerTicker.entrySet()) {
            String ticker = entry.getKey();
            var ann = entry.getValue();
            try {
                resultsEventPublisher.publishResultsFetchEvent(ann, ticker);
                publishedTickers.add(ticker);
                log.info("Published: {} - {}", ticker, ann.getSubject());
            } catch (Exception e) {
                failedTickers.add(ticker);
                log.error("Failed to publish {}: {}", ticker, e.getMessage());
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("days", days);
        response.put("totalFinancialResults", financialResults.size());
        response.put("uniqueTickers", latestPerTicker.size());
        response.put("publishedCount", publishedTickers.size());
        response.put("failedCount", failedTickers.size());
        response.put("publishedTickers", publishedTickers);
        if (!failedTickers.isEmpty()) {
            response.put("failedTickers", failedTickers);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Manually trigger RabbitMQ event for specific tickers.
     * This is useful when announcements were saved but RabbitMQ publish was missed.
     * POST /api/test/results/reprocess
     * Body: { "tickers": ["INTERARCH", "TCS"] }
     */
    @PostMapping("/results/reprocess")
    public ResponseEntity<Map<String, Object>> testReprocessTickers(
            @RequestBody Map<String, Object> body
    ) {
        @SuppressWarnings("unchecked")
        List<String> tickers = (List<String>) body.get("tickers");
        int days = body.get("days") != null ? ((Number) body.get("days")).intValue() : 90;

        if (tickers == null || tickers.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "tickers list is required"
            ));
        }

        log.info("Test: Triggering RabbitMQ reprocess for tickers: {}", tickers);

        List<String> publishedTickers = new ArrayList<>();
        List<String> notFoundTickers = new ArrayList<>();
        List<String> failedTickers = new ArrayList<>();

        for (String ticker : tickers) {
            String cleanTicker = ticker.toUpperCase().trim();
            if (cleanTicker.startsWith("NSE:")) {
                cleanTicker = cleanTicker.substring(4);
            }

            // Find latest financial result announcement for this ticker
            List<com.example.announcement_service.model.Announcement> announcements = announcementRepository
                    .findByNseTickersInAndAfterDate(List.of(cleanTicker), LocalDateTime.now().minusDays(days));

            Optional<com.example.announcement_service.model.Announcement> financialResult = announcements.stream()
                    .filter(this::isFinancialResultAnnouncement)
                    .max(Comparator.comparing(com.example.announcement_service.model.Announcement::getAnnouncementDate));

            if (financialResult.isPresent()) {
                try {
                    resultsEventPublisher.publishResultsFetchEvent(financialResult.get(), cleanTicker);
                    publishedTickers.add(cleanTicker);
                    log.info("Published reprocess event for {} - Subject: {}", cleanTicker,
                            financialResult.get().getSubject());
                } catch (Exception e) {
                    failedTickers.add(cleanTicker);
                    log.error("Failed to publish event for {}: {}", cleanTicker, e.getMessage());
                }
            } else {
                notFoundTickers.add(cleanTicker);
                log.debug("No financial result announcement found for {}", cleanTicker);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestedCount", tickers.size());
        response.put("publishedCount", publishedTickers.size());
        response.put("notFoundCount", notFoundTickers.size());
        response.put("failedCount", failedTickers.size());
        response.put("publishedTickers", publishedTickers);
        if (!notFoundTickers.isEmpty()) {
            response.put("notFoundTickers", notFoundTickers);
        }
        if (!failedTickers.isEmpty()) {
            response.put("failedTickers", failedTickers);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Check if an announcement is a financial result based on subject/category.
     */
    private boolean isFinancialResultAnnouncement(com.example.announcement_service.model.Announcement announcement) {
        String subject = announcement.getSubject() != null
                ? announcement.getSubject().toLowerCase() : "";
        String category = announcement.getCategory() != null
                ? announcement.getCategory().toLowerCase() : "";

        // Direct category match - "Result" category is always a financial result
        if (category.equals("result")) {
            return true;
        }

        for (String keyword : FINANCIAL_RESULT_KEYWORDS) {
            if (subject.contains(keyword) || category.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Debug endpoint to check why an announcement wasn't published to queue.
     * Searches by company name and shows all relevant fields.
     *
     * GET /api/test/debug/announcement?search=kalyan
     */
    @GetMapping("/debug/announcement")
    public ResponseEntity<Map<String, Object>> debugAnnouncement(
            @RequestParam String search,
            @RequestParam(defaultValue = "7") int days) {

        log.info("Debug announcement search: {}", search);

        LocalDateTime afterDate = LocalDateTime.now().minusDays(days);
        List<com.example.announcement_service.model.Announcement> all = announcementRepository.findByAnnouncementDateAfter(afterDate);

        // Filter by search term in company name or subject
        String searchLower = search.toLowerCase();
        List<Map<String, Object>> results = all.stream()
                .filter(a -> (a.getCompanyName() != null && a.getCompanyName().toLowerCase().contains(searchLower))
                        || (a.getSubject() != null && a.getSubject().toLowerCase().contains(searchLower)))
                .map(a -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", a.getId());
                    info.put("newsId", a.getNewsId());
                    info.put("companyName", a.getCompanyName());
                    info.put("subject", a.getSubject());
                    info.put("category", a.getCategory());
                    info.put("scripCode", a.getScripCode());
                    info.put("ticker", a.getTicker());
                    info.put("nseTicker", a.getNseTicker());
                    info.put("announcementDate", a.getAnnouncementDate());
                    info.put("pdfUrl", a.getPdfUrl());

                    // Check if it matches financial result keywords
                    String subj = a.getSubject() != null ? a.getSubject().toLowerCase() : "";
                    String cat = a.getCategory() != null ? a.getCategory().toLowerCase() : "";
                    List<String> matchedKeywords = new ArrayList<>();

                    // Direct category match - "Result" category is always a financial result
                    boolean categoryIsResult = cat.equals("result");
                    if (categoryIsResult) {
                        matchedKeywords.add("category=result");
                    }

                    for (String kw : FINANCIAL_RESULT_KEYWORDS) {
                        if (subj.contains(kw) || cat.contains(kw)) {
                            matchedKeywords.add(kw);
                        }
                    }
                    info.put("isFinancialResult", !matchedKeywords.isEmpty());
                    info.put("matchedKeywords", matchedKeywords);

                    // Check why it might not have been published
                    List<String> issues = new ArrayList<>();
                    if (a.getPdfUrl() == null || a.getPdfUrl().isBlank()) {
                        issues.add("pdfUrl is null/blank - triggerResultsParsingIfNeeded returns early");
                    }
                    if (a.getNseTicker() == null || a.getNseTicker().isBlank()) {
                        issues.add("nseTicker is null - no BSE scrip code mapping exists");
                        // Check if scrip code exists and if mapping is available
                        if (a.getScripCode() != null) {
                            var mappedTicker = tickerMappingService.getTickerForScrip(a.getScripCode());
                            if (mappedTicker.isEmpty()) {
                                issues.add("ScripCode " + a.getScripCode() + " has NO ticker mapping in database");
                            } else {
                                issues.add("ScripCode " + a.getScripCode() + " maps to " + mappedTicker.get() + " but wasn't set on announcement");
                            }
                        }
                    }
                    if (matchedKeywords.isEmpty()) {
                        issues.add("No financial result keywords matched in subject/category");
                    }
                    info.put("potentialIssues", issues);
                    info.put("wouldPublish", matchedKeywords.size() > 0 && a.getNseTicker() != null && !a.getNseTicker().isBlank());

                    return info;
                })
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("search", search);
        response.put("days", days);
        response.put("totalFound", results.size());
        response.put("announcements", results);

        return ResponseEntity.ok(response);
    }

    /**
     * Manually publish a specific announcement to the queue.
     * Use after fixing the ticker mapping.
     *
     * POST /api/test/publish/announcement/{id}?ticker=KALYANKJIL
     */
    @PostMapping("/publish/announcement/{id}")
    public ResponseEntity<Map<String, Object>> publishAnnouncement(
            @PathVariable Long id,
            @RequestParam(required = false) String ticker) {

        var annOpt = announcementRepository.findById(id);
        if (annOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        var ann = annOpt.get();
        String tickerToUse = ticker != null ? ticker : ann.getNseTicker();

        if (tickerToUse == null || tickerToUse.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No ticker available. Provide ticker param or set nseTicker on announcement",
                    "announcementId", id,
                    "companyName", ann.getCompanyName()
            ));
        }

        try {
            resultsEventPublisher.publishResultsFetchEvent(ann, tickerToUse.toUpperCase());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("announcementId", id);
            response.put("ticker", tickerToUse.toUpperCase());
            response.put("companyName", ann.getCompanyName());
            response.put("subject", ann.getSubject());
            response.put("message", "Event published to RabbitMQ queue");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage(),
                    "announcementId", id
            ));
        }
    }

    /**
     * Add a ticker mapping for a BSE scrip code.
     *
     * POST /api/test/mapping?scripCode=543278&ticker=KALYANKJIL
     */
    @PostMapping("/mapping")
    public ResponseEntity<Map<String, Object>> addTickerMapping(
            @RequestParam String scripCode,
            @RequestParam String ticker,
            @RequestParam(required = false) String companyName) {

        tickerMappingService.saveMapping(scripCode, ticker.toUpperCase(), companyName, null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("scripCode", scripCode);
        response.put("ticker", ticker.toUpperCase());
        response.put("message", "Mapping saved. Future announcements for this scrip code will have nseTicker set.");

        return ResponseEntity.ok(response);
    }

    /**
     * Pre-fetch prices for all financial result announcements.
     * This populates the price cache for faster PEAD scanner loads.
     * POST /api/test/prefetch-prices?days=30
     */
    @PostMapping("/prefetch-prices")
    public ResponseEntity<Map<String, Object>> prefetchPrices(
            @RequestParam(defaultValue = "30") int days) {

        log.info("Starting price pre-fetch for announcements from last {} days", days);

        LocalDateTime afterDate = LocalDateTime.now().minusDays(days);
        List<com.example.announcement_service.model.Announcement> announcements =
                announcementRepository.findByAnnouncementDateAfter(afterDate);

        // Filter for financial results with valid tickers
        List<com.example.announcement_service.model.Announcement> financialResults = announcements.stream()
                .filter(a -> a.getNseTicker() != null && !a.getNseTicker().isBlank())
                .filter(this::isFinancialResultAnnouncement)
                .toList();

        // Get unique tickers and dates
        List<String> tickers = financialResults.stream()
                .map(a -> a.getNseTicker().toUpperCase())
                .distinct()
                .toList();

        List<LocalDate> dates = financialResults.stream()
                .filter(a -> a.getAnnouncementDate() != null)
                .map(a -> a.getAnnouncementDate().toLocalDate())
                .distinct()
                .toList();

        // Trigger async batch pre-fetch
        pricePrefetchService.batchPrefetchPrices(tickers, dates);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "started");
        response.put("message", "Price pre-fetch started in background");
        response.put("tickerCount", tickers.size());
        response.put("dateCount", dates.size());
        response.put("days", days);

        return ResponseEntity.ok(response);
    }

    /**
     * Get price cache statistics.
     * GET /api/test/price-cache-stats
     */
    @GetMapping("/price-cache-stats")
    public ResponseEntity<Map<String, Object>> getPriceCacheStats() {
        long totalEntries = priceCacheRepository.count();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalCachedPrices", totalEntries);

        return ResponseEntity.ok(response);
    }

    /**
     * Clear the price cache completely.
     * DELETE /api/test/price-cache
     */
    @DeleteMapping("/price-cache")
    public ResponseEntity<Map<String, Object>> clearPriceCache() {
        long countBefore = priceCacheRepository.count();
        priceCacheRepository.deleteAll();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deleted", countBefore);
        response.put("message", "Price cache cleared. Next PEAD scan will repopulate with only announcement date prices.");

        return ResponseEntity.ok(response);
    }
}
