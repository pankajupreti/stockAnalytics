package com.example.portfolio_service.ai;

// src/main/java/.../ai/SummaryProvider.java
public interface SummaryProvider {
    String generate(SummaryService.Facts facts);
    boolean isAi();
}
