package com.example.announcement_service.controller;

import com.example.announcement_service.model.Announcement;
import com.example.announcement_service.repository.AnnouncementRepository;
import com.example.announcement_service.service.ResultsEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Controller for reprocessing past financial result announcements.
 * Scans the announcement database and publishes events to RabbitMQ
 * for the Python consumer to fetch from Screener.in.
 */
@RestController
@RequestMapping("/api/results")
@RequiredArgsConstructor
@Slf4j
public class ResultsReprocessController {

    private final AnnouncementRepository announcementRepository;
    private final ResultsEventPublisher resultsEventPublisher;

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
            "board meeting",  // Catches "Board Meeting NEWS" etc.
            "results for the quarter",
            "results for quarter"
    );

    /**
     * Reprocess financial result announcements from the last N days.
     * Finds announcements with financial result keywords and publishes
     * RabbitMQ events for each unique ticker.
     *
     * @param days Number of days to look back (default: 30)
     * @return Summary of reprocessed announcements
     */
    @PostMapping("/reprocess")
    public ResponseEntity<Map<String, Object>> reprocessFinancialResults(
            @RequestParam(defaultValue = "30") int days) {

        log.info("Reprocessing financial results from last {} days", days);

        LocalDateTime afterDate = LocalDateTime.now().minusDays(days);
        List<Announcement> allAnnouncements = announcementRepository.findByAnnouncementDateAfter(afterDate);

        // Filter for financial result announcements
        List<Announcement> financialResults = allAnnouncements.stream()
                .filter(this::isFinancialResultAnnouncement)
                .toList();

        log.info("Found {} financial result announcements out of {} total",
                financialResults.size(), allAnnouncements.size());

        // Group by ticker to avoid duplicate events
        Map<String, Announcement> tickerToLatestAnnouncement = new LinkedHashMap<>();
        for (Announcement ann : financialResults) {
            String ticker = ann.getNseTicker();
            if (ticker == null || ticker.isBlank()) {
                ticker = extractTickerFromCompanyName(ann.getCompanyName());
            }
            if (ticker != null && !ticker.isBlank()) {
                // Keep the latest announcement per ticker
                tickerToLatestAnnouncement.merge(ticker.toUpperCase(), ann,
                        (existing, newAnn) -> newAnn.getAnnouncementDate().isAfter(existing.getAnnouncementDate())
                                ? newAnn : existing);
            }
        }

        // Publish events for each unique ticker
        List<String> publishedTickers = new ArrayList<>();
        List<String> failedTickers = new ArrayList<>();

        for (Map.Entry<String, Announcement> entry : tickerToLatestAnnouncement.entrySet()) {
            String ticker = entry.getKey();
            Announcement announcement = entry.getValue();

            try {
                resultsEventPublisher.publishResultsFetchEvent(announcement, ticker);
                publishedTickers.add(ticker);
                log.info("Published reprocess event for {}", ticker);
            } catch (Exception e) {
                failedTickers.add(ticker);
                log.error("Failed to publish event for {}: {}", ticker, e.getMessage());
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalAnnouncements", allAnnouncements.size());
        response.put("financialResultAnnouncements", financialResults.size());
        response.put("uniqueTickers", tickerToLatestAnnouncement.size());
        response.put("publishedCount", publishedTickers.size());
        response.put("failedCount", failedTickers.size());
        response.put("publishedTickers", publishedTickers);
        if (!failedTickers.isEmpty()) {
            response.put("failedTickers", failedTickers);
        }

        log.info("Reprocess complete: {} events published, {} failed",
                publishedTickers.size(), failedTickers.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Reprocess financial results for specific tickers only.
     *
     * @param tickers List of tickers to reprocess
     * @return Summary of reprocessed tickers
     */
    @PostMapping("/reprocess/tickers")
    public ResponseEntity<Map<String, Object>> reprocessByTickers(
            @RequestBody List<String> tickers) {

        log.info("Reprocessing financial results for {} tickers", tickers.size());

        List<String> publishedTickers = new ArrayList<>();
        List<String> notFoundTickers = new ArrayList<>();
        List<String> failedTickers = new ArrayList<>();

        for (String ticker : tickers) {
            String cleanTicker = ticker.toUpperCase().trim();
            if (cleanTicker.startsWith("NSE:")) {
                cleanTicker = cleanTicker.substring(4);
            }

            // Find latest financial result announcement for this ticker
            List<Announcement> announcements = announcementRepository
                    .findByNseTickersInAndAfterDate(List.of(cleanTicker), LocalDateTime.now().minusDays(90));

            Optional<Announcement> financialResult = announcements.stream()
                    .filter(this::isFinancialResultAnnouncement)
                    .max(Comparator.comparing(Announcement::getAnnouncementDate));

            if (financialResult.isPresent()) {
                try {
                    resultsEventPublisher.publishResultsFetchEvent(financialResult.get(), cleanTicker);
                    publishedTickers.add(cleanTicker);
                    log.info("Published reprocess event for {}", cleanTicker);
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
    private boolean isFinancialResultAnnouncement(Announcement announcement) {
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
     * Extract a ticker symbol from company name.
     */
    private String extractTickerFromCompanyName(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return null;
        }
        String[] parts = companyName.trim().split("\\s+");
        if (parts.length > 0) {
            return parts[0].toUpperCase().replaceAll("[^A-Z]", "");
        }
        return null;
    }
}
