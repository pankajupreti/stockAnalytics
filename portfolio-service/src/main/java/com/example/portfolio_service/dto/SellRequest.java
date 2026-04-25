package com.example.portfolio_service.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SellRequest {
    private String ticker;
    private Integer quantity;       // quantity to sell
    private BigDecimal sellPrice;   // price at which selling
    private LocalDate sellDate;     // date of sale (defaults to today)
    private String notes;
}
