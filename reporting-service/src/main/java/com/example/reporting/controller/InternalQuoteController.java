package com.example.reporting.controller;

import com.example.reporting.model.StockAnalytics;
import com.example.reporting.repository.StockAnalyticsRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Internal API for service-to-service calls (no JWT required).
 * Used by alert-service for scheduled price monitoring.
 *
 * IMPORTANT: These endpoints should only be accessible within the internal network.
 * In production, use network policies or API keys for additional security.
 */
@RestController
@RequestMapping("/public/internal")
public class InternalQuoteController {

    private final StockAnalyticsRepository repo;

    public InternalQuoteController(StockAnalyticsRepository repo) {
        this.repo = repo;
    }

    /**
     * Get quotes for multiple tickers (no auth required).
     * GET /public/internal/quotes?tickers=NSE:TCS,NSE:INFY
     *
     * For internal service-to-service calls only.
     */
    @GetMapping("/quotes")
    public List<QuoteDTO> quotes(@RequestParam List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) return List.of();

        var set = tickers.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        return repo.findByTickerIn(set).stream()
                .map(s -> {
                    Double cmp = s.getCmp();
                    Double high52W = s.getHigh52Week();
                    Double low52W = s.getLow52Week();

                    // If 52W high/low not available, estimate from 1Y data
                    // Use cmp365 (price 365 days ago) to estimate rough range
                    if (high52W == null && cmp != null && s.getCmp365() != null && s.getCmp365() > 0) {
                        // Estimate: 52W high = max of current and 1Y ago + 20% buffer
                        high52W = Math.max(cmp, s.getCmp365()) * 1.1;
                    }
                    if (low52W == null && cmp != null && s.getCmp365() != null && s.getCmp365() > 0) {
                        // Estimate: 52W low = min of current and 1Y ago - 10% buffer
                        low52W = Math.min(cmp, s.getCmp365()) * 0.9;
                    }

                    // Calculate pctFrom52WeekHigh (negative means below high)
                    Double pctFrom52WeekHigh = null;
                    if (cmp != null && high52W != null && high52W > 0) {
                        pctFrom52WeekHigh = ((cmp - high52W) / high52W) * 100;
                        pctFrom52WeekHigh = Math.round(pctFrom52WeekHigh * 100.0) / 100.0;
                    }

                    // Calculate pctFrom52WeekLow (positive means above low)
                    Double pctFrom52WeekLow = null;
                    if (cmp != null && low52W != null && low52W > 0) {
                        pctFrom52WeekLow = ((cmp - low52W) / low52W) * 100;
                        pctFrom52WeekLow = Math.round(pctFrom52WeekLow * 100.0) / 100.0;
                    }

                    // RS Rating: Use pre-calculated value from database (1-99 percentile)
                    // This is calculated by RsRatingService comparing all stocks
                    Double rsRating = s.getRsRating();

                    // Fallback if RS Rating not yet calculated: estimate from 52W position
                    if (rsRating == null && cmp != null && high52W != null && low52W != null) {
                        double range = high52W - low52W;
                        if (range > 0) {
                            rsRating = ((cmp - low52W) / range) * 99;  // Scale to 1-99
                            rsRating = Math.max(1.0, Math.min(99.0, rsRating));
                            rsRating = (double) Math.round(rsRating);
                        }
                    }

                    return new QuoteDTO(
                            s.getTicker(),
                            s.getName(),
                            cmp,
                            cmp,
                            s.getDailyChange(),
                            s.getMarketCap(),
                            high52W,
                            low52W,
                            s.getRank1Week(),
                            s.getRank1Month(),
                            s.getRank1Year(),
                            s.getSector(),
                            pctFrom52WeekHigh,
                            pctFrom52WeekLow,
                            rsRating);
                })
                .toList();
    }

    /**
     * Get single quote by ticker (no auth required).
     * GET /public/internal/quotes/price?ticker=NSE:TCS
     */
    @GetMapping("/quotes/price")
    public PriceDTO price(@RequestParam String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return new PriceDTO(null, null);
        }

        String normalized = ticker.trim();
        return repo.findFirstByTickerIgnoreCase(normalized)
                .or(() -> repo.findFirstByTickerIgnoreCase("NSE:" + normalized))
                .map(s -> new PriceDTO(s.getTicker(), s.getCmp()))
                .orElse(new PriceDTO(normalized, null));
    }

    // DTOs - field names must match what consuming services expect
    // portfolio-service expects 'price', alert-service expects 'cmp'
    // Include both fields for compatibility
    public record QuoteDTO(
            String ticker,
            String name,
            Double price,        // Current Market Price (portfolio-service expects 'price')
            Double cmp,          // Current Market Price (alert-service expects 'cmp')
            Double dailyChange,
            Double marketCap,
            Double high52Week,   // 52-week high
            Double low52Week,    // 52-week low
            Double rank1Week,    // 1-week performance rank
            Double rank1Month,   // 1-month performance rank
            Double rank1Year,    // 1-year performance rank
            String sector,       // Stock sector
            Double pctFrom52WeekHigh,  // % from 52W high (negative = below)
            Double pctFrom52WeekLow,   // % from 52W low (positive = above)
            Double rsRating            // RS Rating 0-100
    ) {}

    public record PriceDTO(
            String ticker,
            Double price
    ) {}
}
