package com.example.alert_service.dto;

import lombok.*;

import java.math.BigDecimal;

/**
 * Quote data from reporting service.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuoteDTO {
    private String ticker;
    private String name;
    private BigDecimal cmp;      // Current Market Price
    private BigDecimal dailyChange;
    private BigDecimal marketCap;
}
