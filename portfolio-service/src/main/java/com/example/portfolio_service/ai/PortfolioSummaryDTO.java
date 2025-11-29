package com.example.portfolio_service.ai;

import java.util.List;

// src/main/java/.../ai/PortfolioSummaryDTO.java
public record PortfolioSummaryDTO(
        double dayPercent,           // e.g., 2.16
        double dayValue,             // INR delta (signed)
        List<Move> leaders,          // top positive contributors
        List<Move> laggards,         // top negative contributors
        String text,                 // human-readable summary
        boolean aiGenerated          // true if LLM wrote it
) {
    public record Move(String ticker, double dayPercent, double contributionValue) {}
}
