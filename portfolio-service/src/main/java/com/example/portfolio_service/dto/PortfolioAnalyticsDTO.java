package com.example.portfolio_service.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PortfolioAnalyticsDTO {

    // Summary stats
    private int totalPositions;
    private BigDecimal totalInvested;
    private BigDecimal totalCurrentValue;
    private BigDecimal totalPnlAbs;
    private BigDecimal totalPnlPct;

    // Sector diversification
    private List<SectorAllocation> sectorAllocations;

    // Stock weightage (% of portfolio for each stock)
    private List<StockWeightage> stockWeightages;

    // Performance analytics
    private List<HoldingPerformance> topPerformers;      // top 5 by P&L %
    private List<HoldingPerformance> bottomPerformers;   // bottom 5 by P&L %
    private List<HoldingPerformance> largestHoldings;    // top 5 by market value

    // 52-week analysis
    private List<Week52Analysis> near52WeekHigh;  // within 5% of 52W high
    private List<Week52Analysis> near52WeekLow;   // within 10% of 52W low

    // Risk metrics
    private BigDecimal concentrationRisk;  // % of portfolio in top 3 holdings
    private int sectorCount;               // number of unique sectors

    // Winners vs Losers
    private WinnersLosers winnersLosers;

    // Momentum Analysis
    private MomentumAnalysis momentumAnalysis;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class SectorAllocation {
        private String sector;
        private int holdingsCount;
        private BigDecimal marketValue;
        private BigDecimal percentage;  // % of total portfolio
        private BigDecimal pnlAbs;
        private BigDecimal pnlPct;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class HoldingPerformance {
        private String ticker;
        private String name;
        private String sector;
        private BigDecimal marketValue;
        private BigDecimal pnlAbs;
        private BigDecimal pnlPct;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Week52Analysis {
        private String ticker;
        private String name;
        private BigDecimal cmp;
        private BigDecimal high52Week;
        private BigDecimal low52Week;
        private BigDecimal pctFromHigh;   // how far from 52W high (negative = below)
        private BigDecimal pctFromLow;    // how far from 52W low (positive = above)
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class StockWeightage {
        private String ticker;
        private String name;
        private String sector;
        private BigDecimal currentValue;
        private BigDecimal weightPct;     // % of total portfolio
        private BigDecimal pnlPct;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class WinnersLosers {
        private int winnersCount;
        private int losersCount;
        private int breakEvenCount;
        private BigDecimal winnersValue;      // total value of winning stocks
        private BigDecimal losersValue;       // total value of losing stocks
        private BigDecimal winnersPnl;        // total P&L from winners
        private BigDecimal losersPnl;         // total P&L from losers
        private BigDecimal avgWinnerPct;      // avg P&L% of winners
        private BigDecimal avgLoserPct;       // avg P&L% of losers
        private BigDecimal winRate;           // winners / total * 100
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class MomentumAnalysis {
        // Daily momentum
        private int dailyPositive;
        private int dailyNegative;
        private int dailyNeutral;
        // Weekly momentum
        private int weeklyPositive;
        private int weeklyNegative;
        private int weeklyNeutral;
        // Monthly momentum
        private int monthlyPositive;
        private int monthlyNegative;
        private int monthlyNeutral;
        // Top momentum stocks
        private List<MomentumStock> topDailyMomentum;
        private List<MomentumStock> topWeeklyMomentum;
        private List<MomentumStock> topMonthlyMomentum;
        private List<MomentumStock> worstDailyMomentum;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class MomentumStock {
        private String ticker;
        private String name;
        private BigDecimal dailyChange;
        private BigDecimal weeklyChange;
        private BigDecimal monthlyChange;
    }
}
