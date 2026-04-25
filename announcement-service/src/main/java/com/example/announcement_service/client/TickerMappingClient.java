package com.example.announcement_service.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Component
@Slf4j
public class TickerMappingClient {

    private final WebClient webClient;

    public TickerMappingClient(
            WebClient.Builder builder,
            @Value("${reporting.api.base-url:http://localhost:8082/reporting-service}") String baseUrl
    ) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * Resolve a scrip code or company name to a ticker symbol
     */
    @CircuitBreaker(name = "reportingApi", fallbackMethod = "fallbackResolve")
    public Mono<Optional<String>> resolveToTicker(String query, String bearerToken) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/quotes/resolve")
                        .queryParam("query", query)
                        .build())
                .headers(h -> h.setBearerAuth(bearerToken))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(SymbolDTO.class)
                .map(symbol -> Optional.ofNullable(symbol.getTicker()))
                .onErrorResume(e -> {
                    log.debug("Could not resolve ticker for query: {}", query);
                    return Mono.just(Optional.empty());
                });
    }

    private Mono<Optional<String>> fallbackResolve(String query, String bearerToken, Throwable ex) {
        log.warn("Fallback for ticker resolution: {}", ex.getMessage());
        return Mono.just(Optional.empty());
    }

    @Data
    public static class SymbolDTO {
        private String ticker;
        private String name;
    }
}
