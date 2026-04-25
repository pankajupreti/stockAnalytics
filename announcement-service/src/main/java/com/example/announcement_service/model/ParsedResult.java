package com.example.announcement_service.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity to store quarterly financial results parsed from announcement PDFs.
 * Supports both regular companies and banks/NBFCs with different metrics.
 */
@Entity
@Table(name = "parsed_results",
       uniqueConstraints = @UniqueConstraint(columnNames = {"ticker", "quarter", "fiscal_year"}),
       indexes = {
           @Index(name = "idx_pr_ticker", columnList = "ticker"),
           @Index(name = "idx_pr_fiscal_year", columnList = "fiscal_year"),
           @Index(name = "idx_pr_result_date", columnList = "result_date")
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParsedResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ===== Identity =====
    @Column(nullable = false, length = 50)
    private String ticker;              // RELIANCE (without NSE: prefix)

    @Column(nullable = false, length = 10)
    private String quarter;             // Q1, Q2, Q3, Q4

    @Column(name = "fiscal_year", nullable = false)
    private Integer fiscalYear;         // 2025 for FY25

    @Column(name = "quarter_end_date")
    private LocalDate quarterEndDate;   // 2024-12-31

    @Column(name = "company_type", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private CompanyType companyType = CompanyType.REGULAR;

    // ===== REGULAR COMPANY METRICS (in Crores) =====
    private Double revenue;

    @Column(name = "other_income")
    private Double otherIncome;

    @Column(name = "total_income")
    private Double totalIncome;

    @Column(name = "total_expenses")
    private Double totalExpenses;

    private Double ebitda;

    private Double depreciation;

    @Column(name = "interest_cost")
    private Double interestCost;

    private Double pbt;                 // Profit Before Tax

    private Double tax;

    private Double pat;                 // Profit After Tax (Net Profit)

    // ===== MARGINS (%) =====
    @Column(name = "ebitda_margin")
    private Double ebitdaMargin;        // EBITDA / Revenue * 100

    @Column(name = "pbt_margin")
    private Double pbtMargin;           // PBT / Revenue * 100

    @Column(name = "pat_margin")
    private Double patMargin;           // PAT / Revenue * 100

    // ===== BANK/NBFC SPECIFIC METRICS =====
    private Double nii;                 // Net Interest Income

    private Double nim;                 // Net Interest Margin %

    private Double provisions;          // Loan loss provisions

    private Double gnpa;                // Gross NPA %

    private Double nnpa;                // Net NPA %

    private Double casa;                // CASA Ratio %

    private Double car;                 // Capital Adequacy Ratio %

    // ===== COMMON =====
    @Column(name = "eps_basic")
    private Double epsBasic;

    @Column(name = "eps_diluted")
    private Double epsDiluted;

    // ===== QoQ CHANGES =====
    @Column(name = "revenue_qoq")
    private Double revenueQoQ;          // % change vs previous quarter

    @Column(name = "ebitda_qoq")
    private Double ebitdaQoQ;

    @Column(name = "pat_qoq")
    private Double patQoQ;

    @Column(name = "eps_qoq")
    private Double epsQoQ;

    @Column(name = "ebitda_margin_qoq_pp")
    private Double ebitdaMarginQoQPp;   // percentage points change

    @Column(name = "pat_margin_qoq_pp")
    private Double patMarginQoQPp;

    @Column(name = "nii_qoq")
    private Double niiQoQ;              // for banks

    @Column(name = "nim_qoq_pp")
    private Double nimQoQPp;            // for banks

    // ===== YoY CHANGES =====
    @Column(name = "revenue_yoy")
    private Double revenueYoY;          // % change vs same quarter last year

    @Column(name = "ebitda_yoy")
    private Double ebitdaYoY;

    @Column(name = "pat_yoy")
    private Double patYoY;

    @Column(name = "eps_yoy")
    private Double epsYoY;

    @Column(name = "ebitda_margin_yoy_pp")
    private Double ebitdaMarginYoYPp;

    @Column(name = "pat_margin_yoy_pp")
    private Double patMarginYoYPp;

    @Column(name = "nii_yoy")
    private Double niiYoY;              // for banks

    @Column(name = "nim_yoy_pp")
    private Double nimYoYPp;            // for banks

    // ===== METADATA =====
    @Column(name = "announcement_id")
    private Long announcementId;        // Link to source announcement

    @Column(name = "pdf_url", length = 500)
    private String pdfUrl;

    @Column(name = "result_date")
    private LocalDateTime resultDate;   // When result was announced

    @Column(name = "parsed_at")
    private LocalDateTime parsedAt;

    @Column(name = "parse_status", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ParseStatus parseStatus = ParseStatus.PENDING;

    @Column(length = 500)
    private String remarks;             // Any parsing notes

    // ===== ENUMS =====
    public enum CompanyType {
        REGULAR,    // Normal manufacturing/services company
        BANK,       // Banks
        NBFC,       // NBFCs like Bajaj Finance
        INSURANCE   // Insurance companies
    }

    public enum ParseStatus {
        PENDING,    // Not yet parsed
        SUCCESS,    // Successfully parsed all fields
        PARTIAL,    // Some fields parsed, some missing
        FAILED,     // Parsing failed
        MANUAL      // Manually entered
    }

    // ===== Helper Methods =====

    /**
     * Calculate margins from absolute values.
     */
    public void calculateMargins() {
        if (revenue != null && revenue > 0) {
            if (ebitda != null) {
                this.ebitdaMargin = (ebitda / revenue) * 100;
            }
            if (pbt != null) {
                this.pbtMargin = (pbt / revenue) * 100;
            }
            if (pat != null) {
                this.patMargin = (pat / revenue) * 100;
            }
        }
    }

    /**
     * Get quarter label like "Q3 FY25"
     */
    public String getQuarterLabel() {
        return quarter + " FY" + (fiscalYear % 100);
    }

    /**
     * Check if this is a bank/NBFC company.
     */
    public boolean isFinancialCompany() {
        return companyType == CompanyType.BANK ||
               companyType == CompanyType.NBFC ||
               companyType == CompanyType.INSURANCE;
    }
}
