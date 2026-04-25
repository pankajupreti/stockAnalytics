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
import java.util.stream.Collectors;

/**
 * Client for calling reporting-service to fetch stock analytics data.
 * Used by PEAD scanner to get current prices, 52W high/low, market cap, etc.
 */
@Component
@Slf4j
public class ReportingServiceClient {

    private final WebClient webClient;

    public ReportingServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${reporting.service.url:http://localhost:8083}") String reportingServiceUrl) {
        this.webClient = webClientBuilder
                .baseUrl(reportingServiceUrl)
                .build();
    }

    /**
     * DTO for stock analytics from reporting-service.
     */
    @Data
    public static class StockAnalyticsDTO {
        private String ticker;
        private String name;
        private Double cmp;           // Current Market Price
        private Double price;         // Alias for CMP
        private Double dailyChange;
        private Double marketCap;
        private Double high52Week;
        private Double low52Week;
        private Double rank1Week;
        private Double rank1Month;
        private Double rank1Year;
        private String sector;
        private Double pctFrom52WeekHigh;  // % from 52W high (negative = below)
        private Double pctFrom52WeekLow;   // % from 52W low (positive = above)
        private Double rsRating;           // RS Rating 0-100
    }

    /**
     * Fetch stock analytics for multiple tickers.
     * Uses the internal quotes endpoint.
     *
     * @param tickers List of tickers (e.g., ["RELIANCE", "TCS"])
     * @return Map of ticker -> StockAnalyticsDTO
     */
    public Mono<Map<String, StockAnalyticsDTO>> fetchStockAnalytics(List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) {
            return Mono.just(Collections.emptyMap());
        }

        // Format tickers as NSE:XXX for reporting-service
        List<String> formattedTickers = tickers.stream()
                .map(t -> t.startsWith("NSE:") ? t : "NSE:" + t)
                .collect(Collectors.toList());

        String tickerParam = String.join(",", formattedTickers);

        log.debug("Fetching stock analytics for {} tickers: {}", tickers.size(), tickerParam);

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/public/internal/quotes")
                        .queryParam("tickers", tickerParam)
                        .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<StockAnalyticsDTO>>() {})
                .timeout(Duration.ofSeconds(30))
                .map(list -> {
                    // Convert List to Map keyed by ticker
                    Map<String, StockAnalyticsDTO> map = new java.util.HashMap<>();
                    for (StockAnalyticsDTO dto : list) {
                        if (dto.getTicker() != null) {
                            map.put(dto.getTicker(), dto);
                            // Debug log first few entries
                            if (map.size() <= 3) {
                                log.info("StockAnalytics entry: ticker={}, cmp={}, pctFrom52WeekHigh={}, rsRating={}, rank1Week={}",
                                        dto.getTicker(), dto.getCmp(), dto.getPctFrom52WeekHigh(),
                                        dto.getRsRating(), dto.getRank1Week());
                            }
                        }
                    }
                    log.info("Fetched analytics for {} tickers, map keys sample: {}",
                            map.size(),
                            map.keySet().stream().limit(5).toList());
                    return map;
                })
                .onErrorResume(e -> {
                    log.warn("Failed to fetch stock analytics: {}", e.getMessage());
                    return Mono.just(Collections.emptyMap());
                });
    }

    /**
     * Fetch stock analytics for a single ticker.
     *
     * @param ticker Stock ticker
     * @return StockAnalyticsDTO or null if not found
     */
    public Mono<StockAnalyticsDTO> fetchSingleStock(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return Mono.empty();
        }

        String formattedTicker = ticker.startsWith("NSE:") ? ticker : "NSE:" + ticker;

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/public/internal/quotes/price")
                        .queryParam("ticker", formattedTicker)
                        .build())
                .retrieve()
                .bodyToMono(StockAnalyticsDTO.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(e -> {
                    log.debug("Failed to fetch stock {}: {}", ticker, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Check if reporting-service is available.
     */
    public Mono<Boolean> isServiceAvailable() {
        return webClient.get()
                .uri("/actuator/health")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(5))
                .map(response -> true)
                .onErrorResume(e -> Mono.just(false));
    }
}
