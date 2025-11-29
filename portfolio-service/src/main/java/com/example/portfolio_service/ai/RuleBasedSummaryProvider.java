package com.example.portfolio_service.ai;

import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

// src/main/java/.../ai/RuleBasedSummaryProvider.java
@Component
public class RuleBasedSummaryProvider implements SummaryProvider {

    @Override public String generate(SummaryService.Facts f) {
        String dir = f.dayPercent() >= 0 ? "up" : "down";
        StringBuilder sb = new StringBuilder();
        sb.append("Today your portfolio is ").append(dir).append(" ")
                .append(fmtPct(f.dayPercent())).append(" (").append(fmtInr(f.dayValue())).append(").");

        if (!f.leaders().isEmpty()) {
            sb.append(" Led by ");
            sb.append(f.leaders().stream()
                    .map(m -> m.ticker() + " (" + fmtPct(m.dayPercent()) + ")")
                    .collect(Collectors.joining(", "))).append(".");
        }
        if (!f.laggards().isEmpty()) {
            sb.append(" Biggest drag: ");
            sb.append(f.laggards().stream()
                    .map(m -> m.ticker() + " (" + fmtPct(m.dayPercent()) + ")")
                    .collect(Collectors.joining(", "))).append(".");
        }
        return sb.toString();
    }

    @Override public boolean isAi() { return false; }

    private String fmtPct(double p){ return (p>=0?"+":"") + String.format("%.2f", p) + "%"; }
    private String fmtInr(double v){
        String abs = String.format("%,.2f", Math.abs(v));
        return (v>=0?"+₹ ":"−₹ ") + abs;
    }
}
