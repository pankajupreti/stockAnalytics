package com.example.portfolio_service.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TransactionDTO {
    private Long id;
    private String ticker;
    private String name;           // stock name (enriched)
    private String type;           // BUY or SELL
    private Integer quantity;
    private BigDecimal price;      // transaction price
    private LocalDate transactionDate;
    private LocalDateTime createdAt;
    private BigDecimal realizedPnl;       // for SELL transactions
    private BigDecimal avgBuyPriceAtSell; // for SELL transactions
    private String notes;
    private Long positionId;

    // Calculated fields
    private BigDecimal totalValue;        // quantity * price
}
