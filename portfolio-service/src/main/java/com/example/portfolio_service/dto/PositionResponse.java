package com.example.portfolio_service.dto;



import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PositionResponse {
    private Long id;
    private String ticker;
    private Integer quantity;
    private BigDecimal buyPrice;
    private LocalDate buyDate;
    private String notes;
    private BigDecimal invested;


}
