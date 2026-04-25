package com.example.results.controller;

import com.example.results.dto.QuarterlyResultDTO;
import com.example.results.dto.ResultsSummaryDTO;
import com.example.results.model.QuarterlyResult;
import com.example.results.service.QuarterlyResultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for quarterly results API.
 */
@RestController
@RequestMapping("/api/results")
public class QuarterlyResultController {

    private static final Logger log = LoggerFactory.getLogger(QuarterlyResultController.class);

    private final QuarterlyResultService resultService;

    public QuarterlyResultController(QuarterlyResultService resultService) {
        this.resultService = resultService;
    }

    /**
     * Get all quarterly results for a ticker (ordered by most recent first).
     * Returns full history for detailed analysis view.
     */
    @GetMapping("/ticker/{ticker}")
    public ResponseEntity<Map<String, Object>> getResultsForTicker(@PathVariable String ticker) {
        log.info("Getting results for ticker: {}", ticker);

        List<QuarterlyResult> results = resultService.getResultsForTicker(ticker);
        List<QuarterlyResultDTO> dtos = results.stream()
                .map(QuarterlyResultDTO::fromEntity)
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("ticker", ticker.toUpperCase());
        response.put("results", dtos);
        response.put("count", dtos.size());

        // Determine if it's a bank
        if (!dtos.isEmpty()) {
            response.put("companyType", dtos.get(0).getCompanyType());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get latest result for a ticker.
     */
    @GetMapping("/ticker/{ticker}/latest")
    public ResponseEntity<QuarterlyResultDTO> getLatestResult(@PathVariable String ticker) {
        log.info("Getting latest result for ticker: {}", ticker);

        return resultService.getLatestResult(ticker)
                .map(QuarterlyResultDTO::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get latest results for multiple tickers (for portfolio view).
     * One result per ticker - the most recent quarter.
     */
    @GetMapping("/portfolio")
    public ResponseEntity<Map<String, Object>> getPortfolioResults(
            @RequestParam List<String> tickers) {
        log.info("Getting portfolio results for {} tickers", tickers.size());

        List<QuarterlyResult> results = resultService.getLatestResultsForTickers(tickers);
        List<ResultsSummaryDTO> summaries = results.stream()
                .map(QuarterlyResultDTO::fromEntity)
                .map(ResultsSummaryDTO::fromDTO)
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("results", summaries);
        response.put("count", summaries.size());
        response.put("tickersWithResults", summaries.stream()
                .map(ResultsSummaryDTO::getTicker)
                .collect(Collectors.toList()));

        return ResponseEntity.ok(response);
    }

    /**
     * Get full results for multiple tickers (for comparison view).
     */
    @GetMapping("/compare")
    public ResponseEntity<Map<String, Object>> compareResults(
            @RequestParam List<String> tickers) {
        log.info("Comparing results for {} tickers", tickers.size());

        List<QuarterlyResult> results = resultService.getResultsForTickers(tickers);

        // Group by ticker
        Map<String, List<QuarterlyResultDTO>> byTicker = results.stream()
                .map(QuarterlyResultDTO::fromEntity)
                .collect(Collectors.groupingBy(QuarterlyResultDTO::getTicker));

        Map<String, Object> response = new HashMap<>();
        response.put("resultsByTicker", byTicker);
        response.put("tickers", byTicker.keySet());

        return ResponseEntity.ok(response);
    }

    /**
     * Parse results from PDF and save.
     */
    @PostMapping("/parse")
    public ResponseEntity<QuarterlyResultDTO> parseFromPdf(
            @RequestParam String ticker,
            @RequestParam String pdfUrl,
            @RequestParam(required = false) Long announcementId) {
        log.info("Parsing results for {} from PDF: {}", ticker, pdfUrl);

        QuarterlyResult result = resultService.parseAndSaveFromPdf(ticker, pdfUrl, announcementId);
        return ResponseEntity.ok(QuarterlyResultDTO.fromEntity(result));
    }

    /**
     * Manually save/update a result.
     */
    @PostMapping
    public ResponseEntity<QuarterlyResultDTO> saveResult(@RequestBody QuarterlyResult result) {
        log.info("Saving result for {} {} FY{}", result.getTicker(), result.getQuarter(), result.getFiscalYear());

        QuarterlyResult saved = resultService.saveResult(result);
        return ResponseEntity.ok(QuarterlyResultDTO.fromEntity(saved));
    }

    /**
     * Delete a result.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteResult(@PathVariable Long id) {
        log.info("Deleting result: {}", id);
        resultService.deleteResult(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> status = new HashMap<>();
        status.put("status", "UP");
        status.put("service", "results-service");
        return ResponseEntity.ok(status);
    }
}
