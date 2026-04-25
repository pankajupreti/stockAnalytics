package com.example.portfolio_service.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AddSharesRequest {
    private String ticker;
    private Integer quantity;       // quantity to add
    private BigDecimal buyPrice;    // price at which buying
    private LocalDate buyDate;      // date of purchase (defaults to today)
    private String notes;
}
