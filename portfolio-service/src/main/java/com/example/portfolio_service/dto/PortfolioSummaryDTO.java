package com.example.portfolio_service.dto;



import lombok.*;

import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PortfolioSummaryDTO {
    private int positions;
    private BigDecimal invested;   // sum buyValue
    private BigDecimal current;    // sum marketValue
    private BigDecimal pnlAbs;     // current - invested
    private BigDecimal pnlPct;     // (pnlAbs / invested) * 100
}
