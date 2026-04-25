package com.example.portfolio_service.dto;



import lombok.*;

import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HoldingDTO {
    private Long id;
    private String ticker;
    private String name;

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

    // Sector data
    private String sector;
    private String industry;
    private BigDecimal high52Week;
    private BigDecimal low52Week;
}
