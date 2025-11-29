package com.example.portfolio_service.dto;



import lombok.*;

import org.antlr.v4.runtime.misc.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PositionRequest {

    private String ticker;

    @NotNull

    private Integer quantity;

    @NotNull
    private BigDecimal buyPrice;

    private LocalDate buyDate;
    private String notes;
}
