package com.example.results.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight summary of quarterly results for portfolio view.
 */
@Data
@NoArgsConstructor
public class ResultsSummaryDTO {

    private String ticker;
    private String quarterLabel;  // "Q3 FY25"
    private boolean isBank;

    // Key metrics
    private Double revenueYoY;
    private Double patYoY;
    private Double patMargin;
    private Double epsYoY;

    // For banks
    private Double niiYoY;
    private Double nimChange;

    // Trend indicator
    private String trend;  // "UP", "DOWN", "FLAT"

    private String pdfUrl;
    private String parseStatus;

    /**
     * Create summary from full DTO.
     */
    public static ResultsSummaryDTO fromDTO(QuarterlyResultDTO dto) {
        if (dto == null) return null;

        ResultsSummaryDTO summary = new ResultsSummaryDTO();
        summary.setTicker(dto.getTicker());
        summary.setQuarterLabel(dto.getQuarterLabel());
        summary.setBank("BANK".equals(dto.getCompanyType()) || "NBFC".equals(dto.getCompanyType()));

        summary.setRevenueYoY(dto.getRevenueYoY());
        summary.setPatYoY(dto.getPatYoY());
        summary.setPatMargin(dto.getPatMargin());
        summary.setEpsYoY(dto.getEpsYoY());

        if (summary.isBank()) {
            summary.setNiiYoY(dto.getRevenueYoY()); // NII is like revenue for banks
            summary.setNimChange(dto.getPatMarginYoYPp());
        }

        // Calculate trend
        if (dto.getPatYoY() != null) {
            if (dto.getPatYoY() > 5) {
                summary.setTrend("UP");
            } else if (dto.getPatYoY() < -5) {
                summary.setTrend("DOWN");
            } else {
                summary.setTrend("FLAT");
            }
        }

        summary.setPdfUrl(dto.getPdfUrl());
        summary.setParseStatus(dto.getParseStatus());

        return summary;
    }
}
