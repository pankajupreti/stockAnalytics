package com.example.reporting.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Cache for historical stock prices on specific "anchor" dates.
 * Used for calculating stock movement since significant events (Budget, RBI Policy, etc.)
 *
 * These are HISTORICAL prices (immutable facts) - once fetched, they never change.
 * L2 cache layer (persistent) - sits between L1 (Caffeine) and Yahoo Finance API.
 */
@Entity
@Table(name = "anchor_price_cache")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnchorPriceCache {

    @EmbeddedId
    private AnchorPriceCacheId id;

    @Column(name = "close_price")
    private Double closePrice;

    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;

    /**
     * Composite primary key: ticker + price_date
     */
    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnchorPriceCacheId implements Serializable {

        @Column(name = "ticker", length = 50)
        private String ticker;

        @Column(name = "price_date")
        private LocalDate priceDate;
    }

    /**
     * Convenience constructor
     */
    public static AnchorPriceCache of(String ticker, LocalDate date, Double price) {
        return AnchorPriceCache.builder()
                .id(new AnchorPriceCacheId(ticker.toUpperCase(), date))
                .closePrice(price)
                .fetchedAt(LocalDateTime.now())
                .build();
    }

    public String getTicker() {
        return id != null ? id.getTicker() : null;
    }

    public LocalDate getPriceDate() {
        return id != null ? id.getPriceDate() : null;
    }
}
