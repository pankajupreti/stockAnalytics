package com.example.portfolio_service.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

// src/main/java/.../ai/SummaryOrchestrator.java
@Component
@RequiredArgsConstructor
public class SummaryOrchestrator {

    private final List<SummaryProvider> providers;         // Spring injects both
    private final RuleBasedSummaryProvider fallback;       // explicit fallback

    public String generate(SummaryService.Facts facts) {
        for (var p : providers) {
            if (p.isAi()) {
                var text = p.generate(facts);
                if (text != null && !text.isBlank()) return text;
            }
        }
        return fallback.generate(facts);
    }

    public boolean usedAi() {
        return providers.stream().anyMatch(SummaryProvider::isAi);
    }
}
