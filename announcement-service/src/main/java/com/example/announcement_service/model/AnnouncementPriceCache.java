package com.example.announcement_service.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Cache for historical stock prices on announcement dates.
 * Avoids repeated Yahoo Finance API calls for the same ticker+date combination.
 */
@Entity
@Table(name = "announcement_price_cache",
        uniqueConstraints = @UniqueConstraint(columnNames = {"ticker", "price_date"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementPriceCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String ticker;

    @Column(name = "price_date", nullable = false)
    private LocalDate priceDate;

    @Column(name = "close_price", precision = 12, scale = 2)
    private BigDecimal closePrice;  // NULL if fetch failed

    @Column(name = "fetch_status", length = 20)
    @Enumerated(EnumType.STRING)
    private FetchStatus fetchStatus;

    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;

    @PrePersist
    protected void onCreate() {
        fetchedAt = LocalDateTime.now();
    }

    public enum FetchStatus {
        SUCCESS,      // Price fetched successfully
        NOT_FOUND,    // Ticker not found on Yahoo
        ERROR         // API error (temporary)
    }
}
