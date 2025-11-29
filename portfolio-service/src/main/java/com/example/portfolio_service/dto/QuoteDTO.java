package com.example.portfolio_service.dto;



import lombok.*;

import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuoteDTO {
    private String ticker;
    private BigDecimal price;            // current market price
    private BigDecimal dailyChange;    // %
    private BigDecimal weeklyChange;   // %
    private BigDecimal monthlyChange;  // %
    private BigDecimal marketCap;      // optional
}
