package com.example.reporting.controller;


import com.example.reporting.model.StockAnalytics;


import com.example.reporting.repository.StockAnalyticsRepository;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Quotes endpoints used by the portfolio-service UI for
 * autocomplete, ticker resolution and price lookup.
 */
@RestController
@RequestMapping("/api/quotes")

public class QuoteController {

    private final StockAnalyticsRepository repo;

    public QuoteController(StockAnalyticsRepository repo) {
        this.repo = repo;
    }

    // ----------------------------------------------------
    // 1) Multi-quote (already in your app; kept for compatibility)
    // GET /api/quotes?tickers=NSE:TCS,NSE:INFY
    // ----------------------------------------------------
    @GetMapping
    public List<QuoteDTO> quotes(@RequestParam List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) return List.of();
        // normalize incoming tickers to one case for matching
        var set = tickers.stream().filter(Objects::nonNull)
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        return repo.findByTickerIn(set).stream()
                .map(s -> new QuoteDTO(
                        s.getTicker(),
                        s.getName(),
                        s.getCmp(),
                        s.getDailyChange(),
                        s.getMarketCap()))
                .toList();
    }

    // ----------------------------------------------------
    // 2) Autocomplete search
    // GET /api/quotes/search?q=RELI&limit=10
    // ----------------------------------------------------
    @GetMapping("/search")
    public List<SymbolDTO> search(@RequestParam String q,
                                  @RequestParam(defaultValue = "10") int limit) {
        if (q == null || q.isBlank()) return List.of();
        String query = q.trim();

        // Get a larger chunk, then sort/limit here
        List<StockAnalytics> hits =
                repo.findTop100ByTickerContainingIgnoreCaseOrNameContainingIgnoreCase(query, query);

        // Prefer large market cap first (nulls last)
        hits.sort(Comparator.comparing(
                StockAnalytics::getMarketCap,
                Comparator.nullsLast(Comparator.reverseOrder()))
        );

        return hits.stream()
                .limit(Math.max(1, Math.min(limit, 50)))
                .map(s -> new SymbolDTO(s.getTicker(), s.getName()))
                .toList();
    }

    // ----------------------------------------------------
    // 3) Resolve a loose query to a canonical ticker (404 if unknown)
    // GET /api/quotes/resolve?query=TCS
    // Accepts: TCS, NSE:TCS, BSE:TCS, company name fragments
    // ----------------------------------------------------
    @GetMapping("/resolve")
    public SymbolDTO resolve(@RequestParam String query) {
        return resolveSymbol(query)
                .map(s -> new SymbolDTO(s.getTicker(), s.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Symbol not found"));
    }

    // ----------------------------------------------------
    // 4) Simple price endpoint
    // GET /api/quotes/price?ticker=NSE:TCS
    // ----------------------------------------------------
    @GetMapping("/price")
    public PriceDTO price(@RequestParam String ticker) {
        StockAnalytics s = resolveSymbol(ticker)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Symbol not found"));
        return new PriceDTO(s.getTicker(), s.getCmp());
    }

    // ---------- helpers ----------

    private Optional<StockAnalytics> resolveSymbol(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String q = raw.trim();

        // 1) Exact ticker match (case-insensitive)
        Optional<StockAnalytics> exact = repo.findFirstByTickerIgnoreCase(q);
        if (exact.isPresent()) return exact;

        // 2) Try NSE:/BSE: prefix if user typed plain symbol
        if (!q.contains(":")) {
            exact = repo.findFirstByTickerIgnoreCase("NSE:" + q);
            if (exact.isPresent()) return exact;
            exact = repo.findFirstByTickerIgnoreCase("BSE:" + q);
            if (exact.isPresent()) return exact;
        }

        // 3) Fallback fuzzy search on ticker/name, pick the biggest market cap
        List<StockAnalytics> candidates =
                repo.findTop100ByTickerContainingIgnoreCaseOrNameContainingIgnoreCase(q, q);
        candidates.sort(Comparator.comparing(
                StockAnalytics::getMarketCap,
                Comparator.nullsLast(Comparator.reverseOrder()))
        );
        return candidates.stream().findFirst();
    }

    // ---------- DTOs ----------

    public record QuoteDTO(
            String ticker,
            String name,
            Double price,
            Double dailyChange,
            Double marketCap
    ) {}

    public record SymbolDTO(
            String ticker,
            String name
    ) {}

    public record PriceDTO(
            String ticker,
            Double price
    ) {}
}

