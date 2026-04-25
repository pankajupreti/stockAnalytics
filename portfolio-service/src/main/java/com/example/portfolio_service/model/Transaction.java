package com.example.portfolio_service.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_transactions_user_sub", columnList = "userSub"),
        @Index(name = "idx_transactions_ticker", columnList = "ticker"),
        @Index(name = "idx_transactions_date", columnList = "transactionDate")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** JWT "sub" of owner */
    @Column(nullable = false, length = 64)
    private String userSub;

    @Column(nullable = false, length = 32)
    private String ticker;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionType type;  // BUY or SELL

    @Column(nullable = false)
    private Integer quantity;

    /** Price per share at which transaction occurred */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    /** Date of the transaction */
    @Column(nullable = false)
    private LocalDate transactionDate;

    /** Timestamp when this record was created */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** For SELL: realized P&L for this transaction (calculated at time of sell) */
    @Column(precision = 19, scale = 4)
    private BigDecimal realizedPnl;

    /** For SELL: the average buy price at time of sell (for reference) */
    @Column(precision = 19, scale = 4)
    private BigDecimal avgBuyPriceAtSell;

    /** Optional notes */
    @Column(length = 512)
    private String notes;

    /** Reference to original position (for migration purposes) */
    private Long positionId;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public enum TransactionType {
        BUY, SELL
    }
}
