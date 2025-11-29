package com.example.portfolio_service.dto;



import lombok.*;

import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HoldingDTO {
    private Long id;
    private String ticker;

    private Integer quantity;
    private BigDecimal buyPrice;   // average buy
    private BigDecimal buyValue;   // qty * buyPrice

    private BigDecimal cmp;        // from reporting
    private BigDecimal marketValue;// qty * cmp

    private BigDecimal pnlAbs;     // marketValue - buyValue
    private BigDecimal pnlPct;     // (marketValue - buyValue) / buyValue * 100

    private BigDecimal dailyChange;
    private BigDecimal weeklyChange;
    private BigDecimal monthlyChange;
    private BigDecimal marketCap;
}
