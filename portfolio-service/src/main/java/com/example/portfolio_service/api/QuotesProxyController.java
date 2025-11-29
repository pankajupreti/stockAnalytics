package com.example.portfolio_service.api;


import com.example.portfolio_service.dto.QuoteDTO;
import com.example.portfolio_service.quotes.QuotesClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/portfolio/quotes")
@RequiredArgsConstructor
public class QuotesProxyController {

    private final QuotesClient quotes;

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam String q ,@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(quotes.search(q,jwt.getTokenValue()).block());
    }

  /*  @GetMapping("/batch")
    public ResponseEntity<List<QuoteDTO>> batch(
            @RequestParam List<String> tickers,
            @AuthenticationPrincipal Jwt jwt) {
        var list = quotes.batchQuotes(tickers, jwt.getTokenValue()).block();
        return ResponseEntity.ok(list);
    }*/

    @GetMapping("/batch")
    public Mono<ResponseEntity<BatchQuotesResponse>> batch(
            @RequestParam List<String> tickers,
            @AuthenticationPrincipal Jwt jwt) {

        return quotes.batchQuotes(tickers, jwt.getTokenValue())
                .map(list -> {
                    // Heuristic: if list is empty but tickers were asked, assume fallback/degraded.
                    boolean degraded = list.isEmpty() && !tickers.isEmpty();
                    String source = degraded ? "CACHE_OR_PLACEHOLDER" : "LIVE";

                    return ResponseEntity.ok()
                            .header("X-Data-Quality", degraded ? "DEGRADED" : "LIVE")
                            .body(new BatchQuotesResponse(list, degraded, source));
                })
                // last-resort safety: if the pipeline errors, still return placeholders marked degraded
                .onErrorResume(ex -> {
                    List<QuoteDTO> placeholders = tickers.stream()
                            .map(t -> new QuoteDTO(t, new BigDecimal("100.00"), new BigDecimal("0.00"),new BigDecimal("0.00"),new BigDecimal("0.00"),new BigDecimal("0.00")))
                            .toList();

                    return Mono.just(
                            ResponseEntity.ok()
                                    .header("X-Data-Quality", "DEGRADED")
                                    .body(new BatchQuotesResponse(placeholders, true, "PLACEHOLDER"))
                    );
                });
    }

    public record BatchQuotesResponse(
            List<QuoteDTO> items,
            boolean degraded,           // true if cached/placeholders used
            String source               // "LIVE" | "CACHE" | "PLACEHOLDER"
    ) {}

    @GetMapping("/resolve")
    public ResponseEntity<?> resolve(@RequestParam String query, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(quotes.resolve(query,jwt.getTokenValue()).block());
    }

    @GetMapping("/price")
    public ResponseEntity<?> price(@RequestParam String ticker, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(quotes.price(ticker,jwt.getTokenValue()).block());
    }
}
