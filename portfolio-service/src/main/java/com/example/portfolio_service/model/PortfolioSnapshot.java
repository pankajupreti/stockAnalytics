package com.example.portfolio_service.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Stores daily portfolio value snapshots for historical tracking.
 * Used to display portfolio performance over time vs market indices.
 */
@Entity
@Table(name = "portfolio_snapshots",
    indexes = {
        @Index(name = "idx_snapshot_user_date", columnList = "userSub, snapshotDate")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_date", columnNames = {"userSub", "snapshotDate"})
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PortfolioSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** JWT "sub" of owner */
    @Column(nullable = false, length = 64)
    private String userSub;

    /** Date of the snapshot */
    @Column(nullable = false)
    private LocalDate snapshotDate;

    /** Total portfolio market value on this date */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalValue;

    /** Total invested amount (cost basis) */
    @Column(precision = 19, scale = 2)
    private BigDecimal totalInvested;

    /** Unrealized P&L on this date (current positions only) */
    @Column(precision = 19, scale = 2)
    private BigDecimal unrealizedPnl;

    /** Cumulative realized P&L from all sells up to this date */
    @Column(precision = 19, scale = 2)
    private BigDecimal realizedPnl;

    /** Total wealth = portfolio value + realized P&L (accounts for sold positions) */
    @Column(precision = 19, scale = 2)
    private BigDecimal totalWealth;

    /** Number of positions held */
    private Integer positionsCount;

    /** Normalized value (base 100 from first snapshot) for easy charting */
    @Column(precision = 10, scale = 2)
    private BigDecimal normalizedValue;

    /** When this snapshot was created */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
