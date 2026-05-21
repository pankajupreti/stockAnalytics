package com.example.announcement_service.controller;

import com.example.announcement_service.model.ConcallSummary;
import com.example.announcement_service.service.ConcallService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/announcements/concall")
@RequiredArgsConstructor
@Slf4j
public class ConcallController {

    private final ConcallService concallService;

    /**
     * Batch check which tickers have recent earnings call transcripts.
     * GET /api/announcements/concall/status?tickers=RELIANCE,TCS&days=90
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Map<String, Object>>> getConcallStatus(
            @RequestParam java.util.List<String> tickers,
            @RequestParam(defaultValue = "90") int days
    ) {
        log.debug("Checking concall status for tickers: {}, days: {}", tickers, days);
        Map<String, Map<String, Object>> status = concallService.getConcallStatus(tickers, days);
        return ResponseEntity.ok(status);
    }

    /**
     * Get or generate a concall summary for a specific announcement.
     * GET /api/announcements/concall/summary/{announcementId}
     */
    @GetMapping("/summary/{announcementId}")
    public ResponseEntity<Map<String, Object>> getConcallSummary(
            @PathVariable Long announcementId
    ) {
        log.info("Getting concall summary for announcement: {}", announcementId);
        try {
            ConcallSummary summary = concallService.getOrGenerateSummary(announcementId);
            return ResponseEntity.ok(Map.of(
                    "id", summary.getId(),
                    "announcementId", summary.getAnnouncementId(),
                    "ticker", summary.getTicker() != null ? summary.getTicker() : "",
                    "quarter", summary.getQuarter() != null ? summary.getQuarter() : "",
                    "status", summary.getStatus().name(),
                    "summaryText", summary.getSummaryText() != null ? summary.getSummaryText() : "",
                    "pdfPageCount", summary.getPdfPageCount() != null ? summary.getPdfPageCount() : 0,
                    "textLength", summary.getTextLength() != null ? summary.getTextLength() : 0,
                    "generatedAt", summary.getGeneratedAt() != null ? summary.getGeneratedAt().toString() : ""
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error generating concall summary: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to generate summary: " + e.getMessage()));
        }
    }
}
