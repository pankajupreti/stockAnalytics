package com.example.announcement_service.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

@Component
@Slf4j
public class PortfolioClient {

    private final WebClient webClient;

    public PortfolioClient(
            WebClient.Builder builder,
            @Value("${portfolio.api.base-url:http://localhost:8082/portfolio-service}") String baseUrl
    ) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * Fetch user's portfolio positions to get the list of tickers
     */
    @CircuitBreaker(name = "portfolioApi", fallbackMethod = "fallbackFetchPositions")
    @Retry(name = "portfolioApi")
    public Mono<List<PositionDTO>> fetchUserPositions(String bearerToken) {
        log.debug("Fetching user positions from portfolio service");

        return webClient.get()
                .uri("/api/portfolio/positions")
                .headers(h -> h.setBearerAuth(bearerToken))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToFlux(PositionDTO.class)
                .collectList()
                .doOnSuccess(positions -> log.debug("Fetched {} positions", positions.size()))
                .doOnError(error -> log.error("Error fetching positions: {}", error.getMessage()));
    }

    /**
     * Extract unique tickers from user's portfolio
     */
    public Mono<List<String>> fetchUserTickers(String bearerToken) {
        return fetchUserPositions(bearerToken)
                .map(positions -> positions.stream()
                        .map(PositionDTO::getTicker)
                        .distinct()
                        .toList());
    }

    private Mono<List<PositionDTO>> fallbackFetchPositions(String bearerToken, Throwable ex) {
        log.warn("Fallback triggered for fetchUserPositions: {}", ex.getMessage());
        return Mono.just(Collections.emptyList());
    }

    @Data
    public static class PositionDTO {
        private Long id;
        private String ticker;
        private Integer quantity;
    }
}
