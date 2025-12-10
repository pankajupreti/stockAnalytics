package com.example.portfolio_service.quotes;



import com.example.portfolio_service.dto.QuoteDTO;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.List;



@Component
public class QuotesClient {

    private final WebClient webClient;

    public QuotesClient(
            WebClient.Builder builder,
            @Value("${quotes.api.base}") String base
    ) {
        this.webClient = builder.baseUrl(base).build();
    }

    public Mono<List<SymbolDTO>> search(String q , String bearerToken) {
        return webClient
                .get()
                .uri(uri -> uri.path("/quotes/search").queryParam("q", q).build())
                .headers(h -> h.setBearerAuth(bearerToken))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToFlux(SymbolDTO.class)
                .collectList();
    }

    public Mono<SymbolDTO> resolve(String query,String bearerToken) {
        return webClient
                .get()
                .uri(uri -> uri.path("/quotes/resolve").queryParam("query", query).build())
                .headers(h -> h.setBearerAuth(bearerToken))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(SymbolDTO.class);
    }

    public Mono<PriceDTO> price(String ticker,String bearerToken) {
        return webClient
                .get()
                .uri(uri -> uri.path("/quotes/price").queryParam("ticker", ticker).build())
                .headers(h -> h.setBearerAuth(bearerToken))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(PriceDTO.class);
    }

/*
    public Mono<List<QuoteDTO>> batchQuotes(List<String> tickers, String bearerToken) {
        return webClient.get()
                .uri(uri -> {
                    UriBuilder u = uri.path("/quotes");
                    tickers.forEach(t -> u.queryParam("tickers", t));
                    return u.build();
                })
                .headers(h -> h.setBearerAuth(bearerToken))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToFlux(QuoteDTO.class)
                .collectList();
    }*/



    @Retry(name = "backendRetry")
    @CircuitBreaker(name = "reportingClient", fallbackMethod = "fallbackBatchQuotes")
    public Mono<List<QuoteDTO>> batchQuotes(List<String> tickers, String bearerToken) {
        return webClient.get()
                .uri(uri -> {
                    UriBuilder u = uri.path("/quotes");
                    tickers.forEach(t -> u.queryParam("tickers", t));
                    return u.build();
                })
                .headers(h -> h.setBearerAuth(bearerToken))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToFlux(QuoteDTO.class)
                .collectList();
               // .timeout(Duration.ofMillis(1200)); // extra guard so slow calls fail fast
    }

    // Fallback MUST match args + Throwable at end
    private Mono<List<QuoteDTO>> fallbackBatchQuotes(List<String> tickers,
                                                     String bearerToken,
                                                     Throwable ex) {
        // Optionally log fallback reason
        System.out.printf("⚠️ [Fallback] Using cached/sample quotes due to: %s%n",
                ex.getClass().getSimpleName());
        // Option A: return empty (surface degraded mode to caller/UI)
        return Mono.just(Collections.emptyList());

        // Option B (better): return cached quotes (if you add a cache)
        // return Mono.just(tickers.stream()
        //        .map(t -> cache.getIfPresent(t))
        //        .filter(Objects::nonNull)
        //        .toList());
    }


    @Data public static class SymbolDTO { String ticker; String name; }
    @Data public static class PriceDTO  { String ticker; Double price; }
}
