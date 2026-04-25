package com.example.portfolio_service.dto;



import lombok.*;

import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuoteDTO {
    private String ticker;
    private String name;
    private BigDecimal price;            // current market price
    private BigDecimal dailyChange;    // %
    private BigDecimal weeklyChange;   // %
    private BigDecimal monthlyChange;  // %
    private BigDecimal marketCap;      // optional
    private String sector;
    private String industry;
    private BigDecimal high52Week;
    private BigDecimal low52Week;
}
