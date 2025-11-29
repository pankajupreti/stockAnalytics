// src/main/java/com/example/reporting/breadth/dto/MarketBreadthResponse.java
package com.example.reporting.model;
// src/main/java/com/example/reporting/breadth/dto/MarketBreadthResponse.java


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
}

