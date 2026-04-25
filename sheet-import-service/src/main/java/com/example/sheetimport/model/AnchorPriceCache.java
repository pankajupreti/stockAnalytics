package com.example.sheetimport.model;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Cache for historical stock prices on specific dates.
 * Used for calculating stock movement since significant events (Budget, RBI Policy, etc.)
 * Also stores EOD prices for all stocks.
 */
@Entity
@Table(name = "anchor_price_cache")
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
    public static class AnchorPriceCacheId implements Serializable {

        @Column(name = "ticker", length = 50)
        private String ticker;

        @Column(name = "price_date")
        private LocalDate priceDate;

        public AnchorPriceCacheId() {}

        public AnchorPriceCacheId(String ticker, LocalDate priceDate) {
            this.ticker = ticker;
            this.priceDate = priceDate;
        }

        public String getTicker() { return ticker; }
        public void setTicker(String ticker) { this.ticker = ticker; }
        public LocalDate getPriceDate() { return priceDate; }
        public void setPriceDate(LocalDate priceDate) { this.priceDate = priceDate; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AnchorPriceCacheId that = (AnchorPriceCacheId) o;
            return ticker.equals(that.ticker) && priceDate.equals(that.priceDate);
        }

        @Override
        public int hashCode() {
            return 31 * ticker.hashCode() + priceDate.hashCode();
        }
    }

    public AnchorPriceCache() {}

    public AnchorPriceCache(AnchorPriceCacheId id, Double closePrice, LocalDateTime fetchedAt) {
        this.id = id;
        this.closePrice = closePrice;
        this.fetchedAt = fetchedAt;
    }

    /**
     * Convenience constructor
     */
    public static AnchorPriceCache of(String ticker, LocalDate date, Double price) {
        AnchorPriceCache cache = new AnchorPriceCache();
        cache.id = new AnchorPriceCacheId(ticker.toUpperCase().replace("NSE:", ""), date);
        cache.closePrice = price;
        cache.fetchedAt = LocalDateTime.now();
        return cache;
    }

    public AnchorPriceCacheId getId() { return id; }
    public void setId(AnchorPriceCacheId id) { this.id = id; }
    public Double getClosePrice() { return closePrice; }
    public void setClosePrice(Double closePrice) { this.closePrice = closePrice; }
    public LocalDateTime getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(LocalDateTime fetchedAt) { this.fetchedAt = fetchedAt; }

    public String getTicker() {
        return id != null ? id.getTicker() : null;
    }

    public LocalDate getPriceDate() {
        return id != null ? id.getPriceDate() : null;
    }
}
