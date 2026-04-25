package com.example.announcement_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Response wrapper for PEAD scanner results.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeadScannerResponse {

    private List<PeadStockDTO> stocks;
    private int count;
    private int totalAnnouncements;

    // Applied filters
    private PeadFilters filters;

    // Metadata
    private LocalDateTime generatedAt;
    private String sortedBy;
    private String sortOrder;

    // Summary stats
    private PeadSummary summary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PeadFilters {
        private String quarter;
        private Integer fiscalYear;
        private boolean currentQuarterOnly;
        private Double minRevenueYoY;
        private Double minRevenueQoQ;
        private Double minPatYoY;
        private Double minPatQoQ;
        private Double minPbtYoY;
        private Double minPbtQoQ;
        private Double minMarketCap;
        private Double maxMarketCap;
        private Double minPctChangeSinceResults;
        private String resultType;
        private int days;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PeadSummary {
        private int totalStocks;
        private int positiveGainers;   // Stocks with positive % since results
        private int negativeGainers;   // Stocks with negative % since results
        private Double avgPctChange;   // Average % change since results
        private Double maxPctChange;   // Best performer
        private Double minPctChange;   // Worst performer
        private String topGainer;      // Ticker of best performer
        private String topLoser;       // Ticker of worst performer
    }
}
