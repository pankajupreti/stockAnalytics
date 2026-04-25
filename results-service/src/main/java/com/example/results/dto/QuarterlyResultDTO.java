package com.example.results.dto;

import com.example.results.model.QuarterlyResult;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DTO for quarterly result data transfer.
 */
@Data
@NoArgsConstructor
public class QuarterlyResultDTO {

    private Long id;
    private String ticker;
    private String quarter;
    private Integer fiscalYear;
    private String quarterLabel;  // "Q3 FY25"
    private LocalDate quarterEndDate;
    private String companyType;

    // Metrics (in Crores)
    private Double revenue;
    private Double otherIncome;
    private Double totalIncome;
    private Double totalExpenses;
    private Double ebitda;
    private Double pbt;
    private Double pat;
    private Double epsBasic;

    // Margins
    private Double ebitdaMargin;
    private Double patMargin;

    // Bank-specific
    private Double nii;
    private Double nim;
    private Double gnpa;
    private Double nnpa;

    // QoQ changes
    private Double revenueQoQ;
    private Double ebitdaQoQ;
    private Double patQoQ;
    private Double epsQoQ;
    private Double ebitdaMarginQoQPp;
    private Double patMarginQoQPp;

    // YoY changes
    private Double revenueYoY;
    private Double ebitdaYoY;
    private Double patYoY;
    private Double epsYoY;
    private Double ebitdaMarginYoYPp;
    private Double patMarginYoYPp;

    // Metadata
    private String pdfUrl;
    private LocalDateTime resultDate;
    private String parseStatus;

    /**
     * Create DTO from entity.
     */
    public static QuarterlyResultDTO fromEntity(QuarterlyResult entity) {
        if (entity == null) return null;

        QuarterlyResultDTO dto = new QuarterlyResultDTO();
        dto.setId(entity.getId());
        dto.setTicker(entity.getTicker());
        dto.setQuarter(entity.getQuarter());
        dto.setFiscalYear(entity.getFiscalYear());
        dto.setQuarterLabel(entity.getQuarterLabel());
        dto.setQuarterEndDate(entity.getQuarterEndDate());
        dto.setCompanyType(entity.getCompanyType() != null ? entity.getCompanyType().name() : null);

        dto.setRevenue(entity.getRevenue());
        dto.setOtherIncome(entity.getOtherIncome());
        dto.setTotalIncome(entity.getTotalIncome());
        dto.setTotalExpenses(entity.getTotalExpenses());
        dto.setEbitda(entity.getEbitda());
        dto.setPbt(entity.getPbt());
        dto.setPat(entity.getPat());
        dto.setEpsBasic(entity.getEpsBasic());

        dto.setEbitdaMargin(entity.getEbitdaMargin());
        dto.setPatMargin(entity.getPatMargin());

        dto.setNii(entity.getNii());
        dto.setNim(entity.getNim());
        dto.setGnpa(entity.getGnpa());
        dto.setNnpa(entity.getNnpa());

        dto.setRevenueQoQ(entity.getRevenueQoQ());
        dto.setEbitdaQoQ(entity.getEbitdaQoQ());
        dto.setPatQoQ(entity.getPatQoQ());
        dto.setEpsQoQ(entity.getEpsQoQ());
        dto.setEbitdaMarginQoQPp(entity.getEbitdaMarginQoQPp());
        dto.setPatMarginQoQPp(entity.getPatMarginQoQPp());

        dto.setRevenueYoY(entity.getRevenueYoY());
        dto.setEbitdaYoY(entity.getEbitdaYoY());
        dto.setPatYoY(entity.getPatYoY());
        dto.setEpsYoY(entity.getEpsYoY());
        dto.setEbitdaMarginYoYPp(entity.getEbitdaMarginYoYPp());
        dto.setPatMarginYoYPp(entity.getPatMarginYoYPp());

        dto.setPdfUrl(entity.getPdfUrl());
        dto.setResultDate(entity.getResultDate());
        dto.setParseStatus(entity.getParseStatus() != null ? entity.getParseStatus().name() : null);

        return dto;
    }
}
