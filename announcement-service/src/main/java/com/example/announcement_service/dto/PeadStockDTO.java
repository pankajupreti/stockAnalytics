package com.example.announcement_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO representing a stock in the PEAD (Post Earnings Announcement Drift) scanner.
 * Contains earnings data, price data, and calculated drift metrics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeadStockDTO {

    // Stock identification
    private String ticker;
    private String companyName;

    // Announcement details
    private Long announcementId;
    private String newsId;
    private LocalDateTime announcementDate;
    private String subject;
    private Integer daysSinceAnnouncement;

    // Quarterly results data
    private String quarter;
    private Integer fiscalYear;
    private String quarterLabel;
    private String resultType;  // "consolidated" or "standalone"

    // Financial metrics (in Crores)
    private Double revenue;
    private Double pat;
    private Double pbt;
    private Double ebitda;

    // Growth metrics (%)
    private Double revenueYoY;
    private Double revenueQoQ;
    private Double patYoY;
    private Double patQoQ;
    private Double pbtYoY;
    private Double pbtQoQ;

    // Margin metrics (%)
    private Double patMargin;
    private Double ebitdaMargin;

    // Price & Market data
    private Double currentPrice;
    private Double priceAtAnnouncement;  // Price when results were announced
    private Double marketCap;  // in Crores
    private Double high52Week;
    private Double low52Week;

    // PEAD metrics (the key differentiator)
    private Double pctChangeSinceResults;  // % change in price since announcement
    private Double pctFrom52WeekHigh;      // How far from 52W high (negative = below)
    private Double pctFrom52WeekLow;       // How far from 52W low (positive = above)

    // Relative Strength (basic calculation)
    private Double rsRating;  // 0-100 scale

    // Data source indicators
    private boolean hasStockData;
    private boolean hasResultsData;
}
