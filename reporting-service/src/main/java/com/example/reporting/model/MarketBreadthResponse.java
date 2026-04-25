package com.example.reporting.model;

import java.util.List;

public class MarketBreadthResponse {
    public int total;
    public int green;             // daily_change >= 0
    public int red;               // daily_change < 0
    public double greenPct;       // (green / total) * 100
    public double greenRedRatio;  // green / max(1, red)

    // % change intensity buckets (absolute daily_change)
    public int above3;            // >= +3%
    public int above5;            // >= +5%
    public int above8;            // >= +8%
    public int below3;            // <= -3%
    public int below5;            // <= -5%
    public int below8;            // <= -8%

    // Sector/Industry breakdown - sorted by avg daily change
    public List<SectorStats> sectorLeaders;

    /**
     * Statistics for a single sector/industry
     */
    public static class SectorStats {
        public String sector;
        public int stockCount;
        public int greenCount;
        public int redCount;
        public double avgDailyChange;
        public double greenPct;
        public List<TopStock> topGainers;   // Top 3 gainers in this sector
        public List<TopStock> topLosers;    // Top 3 losers in this sector

        public SectorStats() {}

        public SectorStats(String sector, int stockCount, int greenCount, int redCount,
                          double avgDailyChange, double greenPct,
                          List<TopStock> topGainers, List<TopStock> topLosers) {
            this.sector = sector;
            this.stockCount = stockCount;
            this.greenCount = greenCount;
            this.redCount = redCount;
            this.avgDailyChange = avgDailyChange;
            this.greenPct = greenPct;
            this.topGainers = topGainers;
            this.topLosers = topLosers;
        }
    }

    /**
     * A top performing stock within a sector
     */
    public static class TopStock {
        public String ticker;
        public String name;
        public double dailyChange;
        public Double cmp;
        public Double marketCap;

        public TopStock() {}

        public TopStock(String ticker, String name, double dailyChange, Double cmp, Double marketCap) {
            this.ticker = ticker;
            this.name = name;
            this.dailyChange = dailyChange;
            this.cmp = cmp;
            this.marketCap = marketCap;
        }
    }
}

