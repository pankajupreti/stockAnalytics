package com.example.announcement_service.client;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Client for calling results-service to parse quarterly results from PDFs.
 */
@Component
@Slf4j
public class ResultsServiceClient {

    private final WebClient webClient;
    private final String resultsServiceUrl;

    public ResultsServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${results.service.url:http://localhost:8088}") String resultsServiceUrl) {
        this.resultsServiceUrl = resultsServiceUrl;
        this.webClient = webClientBuilder
                .baseUrl(resultsServiceUrl)
                .build();
    }

    /**
     * Trigger parsing of a financial results PDF.
     * Called asynchronously when a financial result announcement is detected.
     *
     * @param ticker The stock ticker (NSE format preferred)
     * @param pdfUrl URL to the PDF document
     * @param announcementId Optional announcement ID for linking
     * @return Mono<Boolean> - true if parsing was triggered successfully
     */
    public Mono<Boolean> triggerResultsParsing(String ticker, String pdfUrl, Long announcementId) {
        if (ticker == null || pdfUrl == null) {
            return Mono.just(false);
        }

        log.info("Triggering results parsing for ticker={}, pdf={}", ticker, pdfUrl);

        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/results/parse")
                        .queryParam("ticker", ticker)
                        .queryParam("pdfUrl", pdfUrl)
                        .queryParamIfPresent("announcementId",
                                announcementId != null ? java.util.Optional.of(announcementId) : java.util.Optional.empty())
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(60))  // PDF parsing can take time
                .map(response -> {
                    log.info("Results parsing triggered successfully for {}: {}", ticker, response);
                    return true;
                })
                .onErrorResume(e -> {
                    log.warn("Failed to trigger results parsing for {}: {}", ticker, e.getMessage());
                    return Mono.just(false);
                });
    }

    /**
     * Check if results-service is available.
     */
    public Mono<Boolean> isServiceAvailable() {
        return webClient.get()
                .uri("/api/results/health")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(5))
                .map(response -> true)
                .onErrorResume(e -> Mono.just(false));
    }

    /**
     * Trigger auto-refresh of Screener data for a ticker.
     * Called when financial result announcement is detected.
     * The Python results-service will fetch fresh data from Screener.in
     * and cache it for quick access.
     *
     * @param ticker The stock ticker
     * @return Mono<Boolean> - true if refresh was triggered successfully
     */
    public Mono<Boolean> triggerScreenerRefresh(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return Mono.just(false);
        }

        // Python results-service runs on port 8090
        String pythonServiceUrl = resultsServiceUrl.replace(":8088", ":8090");

        log.info("Triggering Screener auto-refresh for ticker={}", ticker);

        return WebClient.create(pythonServiceUrl)
                .post()
                .uri("/api/results/auto-refresh/" + ticker)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .map(response -> {
                    log.info("Screener auto-refresh triggered for {}: {}", ticker, response);
                    return true;
                })
                .onErrorResume(e -> {
                    log.debug("Screener auto-refresh not available for {}: {}", ticker, e.getMessage());
                    return Mono.just(false);
                });
    }

    // ==================== PEAD Scanner Methods ====================

    /**
     * DTO for quarterly results from Python results-service.
     */
    @Data
    public static class QuarterlyResultDTO {
        private String ticker;
        private String quarterLabel;
        private String quarter;
        private Integer fiscalYear;
        private String resultType;
        private Double revenue;
        private Double pat;
        private Double pbt;
        private Double ebitda;
        private Double ebitdaMargin;
        private Double patMargin;
        private Double epsBasic;
        private Double prevYRevenue;
        private Double prevYPat;
        private Double prevYPbt;
        private Double prevQRevenue;
        private Double prevQPat;
        private Double prevQPbt;
        private Double revenueYoY;
        private Double patYoY;
        private Double pbtYoY;
        private Double revenueQoQ;
        private Double patQoQ;
        private Double pbtQoQ;
        private String fetchedAt;
    }

    /**
     * Response wrapper for good-results endpoint.
     */
    @Data
    public static class GoodResultsResponse {
        private List<QuarterlyResultDTO> stocks;
        private int count;
        private Map<String, Object> filters;
    }

    /**
     * Fetch stocks with good quarterly results from Python results-service.
     * This hits the /api/results/good-results endpoint.
     *
     * @param minPatYoY Minimum PAT YoY growth %
     * @param minRevenueYoY Minimum Revenue YoY growth %
     * @param minPatQoQ Minimum PAT QoQ growth %
     * @param minRevenueQoQ Minimum Revenue QoQ growth %
     * @param minPbtYoY Minimum PBT YoY growth %
     * @param minPbtQoQ Minimum PBT QoQ growth %
     * @param currentQuarterOnly Filter to current quarter only
     * @param quarter Specific quarter (Q1/Q2/Q3/Q4)
     * @param fiscalYear Specific fiscal year
     * @param resultType "consolidated" or "standalone"
     * @param days Look back days
     * @param limit Max results
     * @return GoodResultsResponse with matching stocks
     */
    public Mono<GoodResultsResponse> fetchGoodResults(
            Double minPatYoY,
            Double minRevenueYoY,
            Double minPatQoQ,
            Double minRevenueQoQ,
            Double minPbtYoY,
            Double minPbtQoQ,
            boolean currentQuarterOnly,
            String quarter,
            Integer fiscalYear,
            String resultType,
            int days,
            int limit) {

        // Python results-service runs on port 8090
        String pythonServiceUrl = resultsServiceUrl.replace(":8088", ":8090");

        log.debug("Fetching good results from Python service: patYoY>={}, revYoY>={}, pbtYoY>={}, days={}",
                minPatYoY, minRevenueYoY, minPbtYoY, days);

        return WebClient.create(pythonServiceUrl)
                .get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/api/results/good-results");
                    if (minPatYoY != null) builder.queryParam("min_pat_yoy", minPatYoY);
                    if (minRevenueYoY != null) builder.queryParam("min_revenue_yoy", minRevenueYoY);
                    if (minPatQoQ != null) builder.queryParam("min_pat_qoq", minPatQoQ);
                    if (minRevenueQoQ != null) builder.queryParam("min_revenue_qoq", minRevenueQoQ);
                    if (minPbtYoY != null) builder.queryParam("min_pbt_yoy", minPbtYoY);
                    if (minPbtQoQ != null) builder.queryParam("min_pbt_qoq", minPbtQoQ);
                    builder.queryParam("current_quarter_only", currentQuarterOnly);
                    if (quarter != null) builder.queryParam("quarter", quarter);
                    if (fiscalYear != null) builder.queryParam("fiscal_year", fiscalYear);
                    if (resultType != null) builder.queryParam("result_type", resultType);
                    builder.queryParam("days", days);
                    builder.queryParam("limit", limit);
                    return builder.build();
                })
                .retrieve()
                .bodyToMono(GoodResultsResponse.class)
                .timeout(Duration.ofSeconds(30))
                .doOnNext(r -> log.debug("Fetched {} good results", r.getCount()))
                .onErrorResume(e -> {
                    log.warn("Failed to fetch good results: {}", e.getMessage());
                    GoodResultsResponse empty = new GoodResultsResponse();
                    empty.setStocks(Collections.emptyList());
                    empty.setCount(0);
                    return Mono.just(empty);
                });
    }

    /**
     * Fetch quarterly results for a specific ticker.
     *
     * @param ticker Stock ticker
     * @return Map with results data
     */
    public Mono<Map<String, Object>> fetchTickerResults(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return Mono.just(Collections.emptyMap());
        }

        // Python results-service runs on port 8090
        String pythonServiceUrl = resultsServiceUrl.replace(":8088", ":8090");

        return WebClient.create(pythonServiceUrl)
                .get()
                .uri("/api/results/ticker/" + ticker.toUpperCase())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(15))
                .onErrorResume(e -> {
                    log.debug("Failed to fetch results for {}: {}", ticker, e.getMessage());
                    return Mono.just(Collections.emptyMap());
                });
    }
}
