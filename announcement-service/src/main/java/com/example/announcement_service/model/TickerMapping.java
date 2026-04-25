package com.example.announcement_service.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Entity to store BSE scrip code to NSE ticker symbol mappings.
 * This allows us to match BSE announcements with portfolio stocks.
 */
@Entity
@Table(name = "ticker_mappings", indexes = {
        @Index(name = "idx_ticker_mapping_scrip", columnList = "scripCode"),
        @Index(name = "idx_ticker_mapping_nse", columnList = "nseTicker"),
        @Index(name = "idx_ticker_mapping_isin", columnList = "isin")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TickerMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * BSE scrip code (e.g., "500325")
     */
    @Column(nullable = false, length = 20, unique = true)
    private String scripCode;

    /**
     * NSE ticker symbol (e.g., "RELIANCE")
     */
    @Column(length = 50)
    private String nseTicker;

    /**
     * BSE ticker symbol (e.g., "RELIANCE")
     */
    @Column(length = 50)
    private String bseTicker;

    /**
     * ISIN code - unique identifier across exchanges (e.g., "INE002A01018")
     */
    @Column(length = 20)
    private String isin;

    /**
     * Company name from BSE
     */
    @Column(length = 256)
    private String companyName;

    /**
     * Industry/sector
     */
    @Column(length = 128)
    private String industry;

    /**
     * Whether this stock is actively traded
     */
    @Column
    private Boolean active;

    @Column
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
