package com.example.portfolio_service.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import org.springframework.core.ParameterizedTypeReference;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class AnnouncementClient {

    private final WebClient webClient;

    public AnnouncementClient(
            WebClient.Builder builder,
            @Value("${announcement.api.base-url:http://localhost:8082/announcement-service}") String baseUrl
    ) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * Check which tickers from a list have recent announcements
     */
    @CircuitBreaker(name = "announcementClient", fallbackMethod = "fallbackCheckTickers")
    @Retry(name = "announcementClient")
    public Mono<Map<String, Boolean>> checkTickersForAnnouncements(List<String> tickers, int days, String bearerToken) {
        if (tickers == null || tickers.isEmpty()) {
            return Mono.just(Collections.emptyMap());
        }

        log.debug("Checking announcements for tickers: {}", tickers);

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/announcements/check")
                        .queryParam("tickers", String.join(",", tickers))
                        .queryParam("days", days)
                        .build())
                .headers(h -> h.setBearerAuth(bearerToken))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Boolean>>() {})
                .doOnSuccess(result -> log.debug("Announcement check result: {}", result))
                .doOnError(error -> log.error("Error checking announcements: {}", error.getMessage()));
    }

    /**
     * Get announcements for specific tickers
     */
    @CircuitBreaker(name = "announcementClient", fallbackMethod = "fallbackGetAnnouncements")
    @Retry(name = "announcementClient")
    public Mono<List<AnnouncementDTO>> getAnnouncementsByTickers(List<String> tickers, int days, String bearerToken) {
        if (tickers == null || tickers.isEmpty()) {
            return Mono.just(Collections.emptyList());
        }

        log.debug("Fetching announcements for tickers: {}", tickers);

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/announcements/by-tickers")
                        .queryParam("tickers", String.join(",", tickers))
                        .queryParam("days", days)
                        .build())
                .headers(h -> h.setBearerAuth(bearerToken))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToFlux(AnnouncementDTO.class)
                .collectList()
                .doOnSuccess(result -> log.debug("Fetched {} announcements", result.size()))
                .doOnError(error -> log.error("Error fetching announcements: {}", error.getMessage()));
    }

    /**
     * Get portfolio announcements (grouped by ticker)
     */
    @CircuitBreaker(name = "announcementClient", fallbackMethod = "fallbackGetPortfolioAnnouncements")
    @Retry(name = "announcementClient")
    public Mono<List<PortfolioAnnouncementDTO>> getPortfolioAnnouncements(int days, int maxPerTicker, String bearerToken) {
        log.debug("Fetching portfolio announcements");

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/announcements/portfolio")
                        .queryParam("days", days)
                        .queryParam("maxPerTicker", maxPerTicker)
                        .build())
                .headers(h -> h.setBearerAuth(bearerToken))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToFlux(PortfolioAnnouncementDTO.class)
                .collectList()
                .doOnError(error -> log.error("Error fetching portfolio announcements: {}", error.getMessage()));
    }

    // Fallback methods
    private Mono<Map<String, Boolean>> fallbackCheckTickers(List<String> tickers, int days, String bearerToken, Throwable ex) {
        log.warn("Fallback for checkTickersForAnnouncements: {}", ex.getMessage());
        return Mono.just(Collections.emptyMap());
    }

    private Mono<List<AnnouncementDTO>> fallbackGetAnnouncements(List<String> tickers, int days, String bearerToken, Throwable ex) {
        log.warn("Fallback for getAnnouncementsByTickers: {}", ex.getMessage());
        return Mono.just(Collections.emptyList());
    }

    private Mono<List<PortfolioAnnouncementDTO>> fallbackGetPortfolioAnnouncements(int days, int maxPerTicker, String bearerToken, Throwable ex) {
        log.warn("Fallback for getPortfolioAnnouncements: {}", ex.getMessage());
        return Mono.just(Collections.emptyList());
    }

    @Data
    public static class AnnouncementDTO {
        private Long id;
        private String scripCode;
        private String ticker;
        private String companyName;
        private String subject;
        private String category;
        private String subCategory;
        private LocalDateTime announcementDate;
        private String pdfUrl;
        private String newsId;
    }

    @Data
    public static class PortfolioAnnouncementDTO {
        private String ticker;
        private String companyName;
        private int announcementCount;
        private List<AnnouncementDTO> recentAnnouncements;
    }
}
