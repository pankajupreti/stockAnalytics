package com.example.announcement_service.controller;

import com.example.announcement_service.service.PdfParserService;
import com.example.announcement_service.service.ResultsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for quarterly financial results.
 * Provides endpoints to fetch and parse financial results from announcement PDFs.
 */
@RestController
@RequestMapping("/api/results")
@RequiredArgsConstructor
@Slf4j
public class ResultsController {

    private final ResultsService resultsService;
    private final PdfParserService pdfParserService;

    /**
     * Get quarterly results for a specific ticker.
     * If no cached results exist, triggers parsing from available announcements.
     *
     * GET /api/results/ticker/{ticker}
     */
    @GetMapping("/ticker/{ticker}")
    public ResponseEntity<Map<String, Object>> getResultsForTicker(@PathVariable String ticker) {
        log.info("Getting results for ticker: {}", ticker);
        Map<String, Object> results = resultsService.getResultsForTicker(ticker);
        return ResponseEntity.ok(results);
    }

    /**
     * Get quarterly results for multiple tickers (portfolio view).
     *
     * GET /api/results/compare?tickers=RELIANCE&tickers=TCS&tickers=INFY
     */
    @GetMapping("/compare")
    public ResponseEntity<Map<String, Object>> getResultsForTickers(
            @RequestParam List<String> tickers) {
        log.info("Getting results for {} tickers", tickers.size());
        Map<String, Object> results = resultsService.getResultsForTickers(tickers);
        return ResponseEntity.ok(results);
    }

    /**
     * Manually trigger PDF parsing for a specific ticker and PDF URL.
     *
     * POST /api/results/parse?ticker=NETWEB&pdfUrl=...&announcementId=123
     */
    @PostMapping("/parse")
    public ResponseEntity<Map<String, Object>> triggerParsing(
            @RequestParam String ticker,
            @RequestParam String pdfUrl,
            @RequestParam(required = false) Long announcementId) {
        log.info("Manual parsing triggered for ticker: {}", ticker);

        var result = resultsService.triggerParsing(ticker, pdfUrl, announcementId);

        if (result != null) {
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "ticker", result.getTicker(),
                    "quarter", result.getQuarterLabel(),
                    "revenue", result.getRevenue() != null ? result.getRevenue() : 0,
                    "pat", result.getPat() != null ? result.getPat() : 0,
                    "parseStatus", result.getParseStatus().name()
            ));
        } else {
            return ResponseEntity.ok(Map.of(
                    "status", "failed",
                    "message", "Could not parse results from PDF"
            ));
        }
    }

    /**
     * Debug endpoint to see raw PDF text extraction.
     * Use this to diagnose parsing issues.
     *
     * GET /api/results/debug?pdfUrl=...
     */
    @GetMapping("/debug")
    public ResponseEntity<Map<String, Object>> debugPdfExtraction(@RequestParam String pdfUrl) {
        log.info("Debug PDF extraction for: {}", pdfUrl);

        try {
            String rawText = pdfParserService.downloadAndExtractText(pdfUrl);

            if (rawText == null || rawText.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "status", "failed",
                        "message", "Could not extract text from PDF"
                ));
            }

            String consolidatedSection = pdfParserService.extractConsolidatedSection(rawText);
            var metrics = pdfParserService.parseMetrics(consolidatedSection);
            String[] quarterInfo = pdfParserService.extractQuarterInfo(rawText);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "rawTextLength", rawText.length(),
                    "rawTextPreview", rawText.substring(0, Math.min(1000, rawText.length())),
                    "consolidatedSectionLength", consolidatedSection != null ? consolidatedSection.length() : 0,
                    "consolidatedPreview", consolidatedSection != null ? consolidatedSection.substring(0, Math.min(1000, consolidatedSection.length())) : "",
                    "parsedMetrics", metrics,
                    "quarter", quarterInfo != null ? quarterInfo[0] + " FY" + quarterInfo[1] : "unknown"
            ));
        } catch (Exception e) {
            log.error("Debug extraction failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Fetch results directly from Screener.in (force refresh).
     * Use this to bypass PDF parsing and get data from Screener.
     *
     * GET /api/results/screener/{ticker}
     */
    @GetMapping("/screener/{ticker}")
    public ResponseEntity<Map<String, Object>> getFromScreener(@PathVariable String ticker) {
        log.info("Fetching from Screener for ticker: {}", ticker);
        Map<String, Object> results = resultsService.refreshFromScreener(ticker);
        return ResponseEntity.ok(results);
    }

    /**
     * Debug Screener scraping directly - use this to test if scraping works.
     * Returns raw scraped data and any errors encountered.
     *
     * GET /api/results/debug-screener/{ticker}
     */
    @GetMapping("/debug-screener/{ticker}")
    public ResponseEntity<Map<String, Object>> debugScreenerScraping(@PathVariable String ticker) {
        log.info("Debug Screener scraping for ticker: {}", ticker);

        try {
            var results = resultsService.refreshFromScreener(ticker);

            // Add extra debug info
            results.put("debugInfo", Map.of(
                    "timestamp", java.time.LocalDateTime.now().toString(),
                    "tickerUsed", ticker.toUpperCase()
            ));

            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("Debug Screener scraping failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                    "status", "error",
                    "message", e.getMessage(),
                    "ticker", ticker
            ));
        }
    }

    /**
     * Force refresh from both sources - PDF first (primary), Screener as fallback.
     *
     * POST /api/results/refresh/{ticker}
     */
    @PostMapping("/refresh/{ticker}")
    public ResponseEntity<Map<String, Object>> forceRefresh(@PathVariable String ticker) {
        log.info("Force refreshing results for ticker: {} (PDF first, Screener fallback)", ticker);

        // This calls getResultsForTicker which already tries PDF first, then Screener
        // But we want to bypass cache, so we call the parsing directly
        Map<String, Object> results = resultsService.forceRefreshResults(ticker);

        return ResponseEntity.ok(results);
    }

    /**
     * Health check endpoint.
     *
     * GET /api/results/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "results-api"
        ));
    }

    /**
     * Test endpoint to parse a local PDF file.
     * For development/debugging only.
     *
     * GET /api/results/test-local?filePath=C:\proj\OauthProj\result\anantraj.pdf
     */
    @GetMapping("/test-local")
    public ResponseEntity<Map<String, Object>> testLocalPdf(@RequestParam String filePath) {
        log.info("Testing local PDF parsing for: {}", filePath);

        try {
            Map<String, Object> result = pdfParserService.parseLocalPdf(filePath);
            result.put("status", "success");
            result.put("filePath", filePath);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Local PDF parsing failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                    "status", "error",
                    "message", e.getMessage(),
                    "filePath", filePath
            ));
        }
    }
}
