package com.example.alert_service.client;

import com.example.alert_service.dto.QuoteDTO;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Client for fetching live prices from reporting-service.
 * Supports two modes:
 * 1. Authenticated calls (with JWT) - via gateway for user-initiated requests
 * 2. Internal calls (no JWT) - direct to reporting-service for scheduled monitoring
 */
@Component
@Slf4j
public class ReportingClient {

    private final WebClient gatewayClient;   // Via gateway, requires JWT
    private final WebClient internalClient;  // Direct to reporting-service, no JWT needed

    public ReportingClient(
            WebClient.Builder builder,
            @Value("${reporting.api.base-url:http://localhost:8082/reporting-service}") String gatewayUrl,
            @Value("${reporting.api.internal-url:http://localhost:8083}") String internalUrl
    ) {
        this.gatewayClient = builder.clone()
                .baseUrl(gatewayUrl)
                .build();
        this.internalClient = builder.clone()
                .baseUrl(internalUrl)
                .build();

        log.info("ReportingClient initialized - gateway: {}, internal: {}", gatewayUrl, internalUrl);
    }

    /**
     * Fetch quotes for multiple tickers with JWT token forwarding.
     * Used for user-initiated requests where JWT is available.
     * Goes through gateway which validates JWT.
     *
     * @param tickers List of tickers (e.g., ["NSE:TCS", "NSE:RELIANCE"])
     * @param bearerToken JWT token from incoming request
     * @return Map of ticker -> QuoteDTO
     */
    @CircuitBreaker(name = "reportingClient", fallbackMethod = "fallbackGetQuotes")
    public Map<String, QuoteDTO> getQuotes(List<String> tickers, String bearerToken) {
        if (tickers == null || tickers.isEmpty()) {
            return Collections.emptyMap();
        }

        String tickersParam = String.join(",", tickers);
        log.debug("Fetching quotes (via gateway) for: {}", tickersParam);

        try {
            List<QuoteDTO> quotes = gatewayClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/quotes")
                            .queryParam("tickers", tickersParam)
                            .build())
                    .headers(h -> h.setBearerAuth(bearerToken))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToFlux(QuoteDTO.class)
                    .retryWhen(Retry.backoff(3, Duration.ofMillis(500)))
                    .collectList()
                    .block();

            return mapQuotes(quotes);
        } catch (Exception e) {
            log.error("Error fetching quotes (via gateway): {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Fetch quotes for multiple tickers without authentication.
     * Used for scheduled internal monitoring (no user context).
     * Calls reporting-service DIRECTLY (bypassing gateway) on /public/internal/quotes.
     *
     * @param tickers List of tickers (e.g., ["NSE:TCS", "NSE:RELIANCE"])
     * @return Map of ticker -> QuoteDTO
     */
    @CircuitBreaker(name = "reportingClient", fallbackMethod = "fallbackGetQuotesInternal")
    public Map<String, QuoteDTO> getQuotesInternal(List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) {
            return Collections.emptyMap();
        }

        String tickersParam = String.join(",", tickers);
        log.debug("Fetching quotes (direct internal) for: {}", tickersParam);

        try {
            List<QuoteDTO> quotes = internalClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/public/internal/quotes")
                            .queryParam("tickers", tickersParam)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToFlux(QuoteDTO.class)
                    .retryWhen(Retry.backoff(3, Duration.ofMillis(500)))
                    .collectList()
                    .block();

            return mapQuotes(quotes);
        } catch (Exception e) {
            log.error("Error fetching quotes (direct internal): {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Fetch quote for a single ticker with JWT.
     */
    public QuoteDTO getQuote(String ticker, String bearerToken) {
        Map<String, QuoteDTO> quotes = getQuotes(List.of(ticker), bearerToken);
        return quotes.get(ticker.toUpperCase());
    }

    /**
     * Fetch quote for a single ticker (internal, no JWT).
     */
    public QuoteDTO getQuoteInternal(String ticker) {
        Map<String, QuoteDTO> quotes = getQuotesInternal(List.of(ticker));
        return quotes.get(ticker.toUpperCase());
    }

    private Map<String, QuoteDTO> mapQuotes(List<QuoteDTO> quotes) {
        if (quotes == null) {
            return Collections.emptyMap();
        }

        return quotes.stream()
                .filter(q -> q.getTicker() != null)
                .collect(Collectors.toMap(
                        q -> q.getTicker().toUpperCase(),
                        q -> q,
                        (q1, q2) -> q1
                ));
    }

    /**
     * Fallback when reporting service is unavailable (authenticated).
     */
    private Map<String, QuoteDTO> fallbackGetQuotes(List<String> tickers, String bearerToken, Throwable ex) {
        log.warn("Fallback triggered for getQuotes (authenticated) due to: {}", ex.getMessage());
        return Collections.emptyMap();
    }

    /**
     * Fallback when reporting service is unavailable (internal).
     */
    private Map<String, QuoteDTO> fallbackGetQuotesInternal(List<String> tickers, Throwable ex) {
        log.warn("Fallback triggered for getQuotesInternal due to: {}", ex.getMessage());
        return Collections.emptyMap();
    }
}
