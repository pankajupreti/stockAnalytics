package com.example.results.service;

import com.example.results.model.QuarterlyResult;
import com.example.results.model.QuarterlyResult.CompanyType;
import com.example.results.model.QuarterlyResult.ParseStatus;
import com.example.results.repository.QuarterlyResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for managing quarterly financial results.
 */
@Service
public class QuarterlyResultService {

    private static final Logger log = LoggerFactory.getLogger(QuarterlyResultService.class);

    private final QuarterlyResultRepository repository;
    private final PdfParserService pdfParserService;

    // List of known bank/NBFC tickers
    private static final Set<String> BANK_TICKERS = Set.of(
            "HDFCBANK", "ICICIBANK", "KOTAKBANK", "SBIN", "AXISBANK",
            "INDUSINDBK", "BANDHANBNK", "IDFCFIRSTB", "FEDERALBNK", "PNB",
            "BANKBARODA", "CANBK", "UNIONBANK", "IOB", "INDIANB",
            "YESBANK", "RBLBANK", "IDBI", "AUBANK", "EQUITASBNK"
    );

    private static final Set<String> NBFC_TICKERS = Set.of(
            "BAJFINANCE", "BAJAJFINSV", "CHOLAFIN", "LICHSGFIN", "MUTHOOTFIN",
            "MANAPPURAM", "SHRIRAMFIN", "M&MFIN", "L&TFH", "POONAWALLA",
            "IIFL", "CREDITACC", "EDELWEISS", "HDFCAMC", "ICICIGI"
    );

    public QuarterlyResultService(QuarterlyResultRepository repository,
                                   PdfParserService pdfParserService) {
        this.repository = repository;
        this.pdfParserService = pdfParserService;
    }

    /**
     * Parse results from a PDF URL and save to database.
     */
    @Transactional
    public QuarterlyResult parseAndSaveFromPdf(String ticker, String pdfUrl, Long announcementId) {
        log.info("Parsing results for {} from PDF: {}", ticker, pdfUrl);

        String cleanTicker = cleanTicker(ticker);

        // Download and extract text
        String fullText = pdfParserService.downloadAndExtractText(pdfUrl);
        if (fullText == null || fullText.isEmpty()) {
            log.error("Failed to extract text from PDF for {}", cleanTicker);
            return createFailedResult(cleanTicker, pdfUrl, announcementId, "Failed to extract text from PDF");
        }

        // Extract consolidated section
        String consolidatedText = pdfParserService.extractConsolidatedSection(fullText);

        // Detect company type
        CompanyType companyType = detectCompanyType(cleanTicker, fullText);

        // Extract quarter info
        String[] quarterInfo = pdfParserService.extractQuarterInfo(fullText);
        String quarter = quarterInfo != null ? quarterInfo[0] : "Q3";
        Integer fiscalYear = quarterInfo != null ? Integer.parseInt(quarterInfo[1]) : LocalDateTime.now().getYear();

        // Check if result already exists
        Optional<QuarterlyResult> existing = repository.findByTickerAndQuarterAndFiscalYear(
                cleanTicker, quarter, fiscalYear);
        if (existing.isPresent()) {
            log.info("Result already exists for {} {} FY{}", cleanTicker, quarter, fiscalYear);
            return existing.get();
        }

        // Parse metrics
        Map<String, List<Double>> metrics = pdfParserService.parseMetrics(consolidatedText);

        // Create result entity
        QuarterlyResult result = new QuarterlyResult();
        result.setTicker(cleanTicker);
        result.setQuarter(quarter);
        result.setFiscalYear(fiscalYear);
        result.setCompanyType(companyType);
        result.setPdfUrl(pdfUrl);
        result.setAnnouncementId(announcementId);
        result.setResultDate(LocalDateTime.now());
        result.setParsedAt(LocalDateTime.now());

        // Map parsed metrics to entity
        int fieldsPopulated = populateMetrics(result, metrics, companyType);

        // Calculate margins
        result.calculateMargins();

        // Set parse status based on how many fields we got
        if (fieldsPopulated >= 4) {
            result.setParseStatus(ParseStatus.SUCCESS);
            result.setRemarks("Parsed " + fieldsPopulated + " metrics");
        } else if (fieldsPopulated > 0) {
            result.setParseStatus(ParseStatus.PARTIAL);
            result.setRemarks("Only " + fieldsPopulated + " metrics found");
        } else {
            result.setParseStatus(ParseStatus.FAILED);
            result.setRemarks("No metrics could be extracted");
        }

        // Save
        QuarterlyResult saved = repository.save(result);
        log.info("Saved result for {} {} FY{} with status {}",
                 cleanTicker, quarter, fiscalYear, result.getParseStatus());

        // Calculate QoQ and YoY changes
        calculateGrowthRates(saved);

        return saved;
    }

    /**
     * Populate entity fields from parsed metrics.
     */
    private int populateMetrics(QuarterlyResult result, Map<String, List<Double>> metrics, CompanyType companyType) {
        int count = 0;

        // For each metric, take the first value (current quarter)
        if (metrics.containsKey("revenue") && !metrics.get("revenue").isEmpty()) {
            result.setRevenue(metrics.get("revenue").get(0));
            count++;
        }
        if (metrics.containsKey("otherIncome") && !metrics.get("otherIncome").isEmpty()) {
            result.setOtherIncome(metrics.get("otherIncome").get(0));
            count++;
        }
        if (metrics.containsKey("totalIncome") && !metrics.get("totalIncome").isEmpty()) {
            result.setTotalIncome(metrics.get("totalIncome").get(0));
            count++;
        }
        if (metrics.containsKey("totalExpenses") && !metrics.get("totalExpenses").isEmpty()) {
            result.setTotalExpenses(metrics.get("totalExpenses").get(0));
            count++;
        }
        if (metrics.containsKey("pbt") && !metrics.get("pbt").isEmpty()) {
            result.setPbt(metrics.get("pbt").get(0));
            count++;
        }
        if (metrics.containsKey("tax") && !metrics.get("tax").isEmpty()) {
            result.setTax(metrics.get("tax").get(0));
            count++;
        }
        if (metrics.containsKey("pat") && !metrics.get("pat").isEmpty()) {
            result.setPat(metrics.get("pat").get(0));
            count++;
        }
        if (metrics.containsKey("epsBasic") && !metrics.get("epsBasic").isEmpty()) {
            result.setEpsBasic(metrics.get("epsBasic").get(0));
            count++;
        }
        if (metrics.containsKey("epsDiluted") && !metrics.get("epsDiluted").isEmpty()) {
            result.setEpsDiluted(metrics.get("epsDiluted").get(0));
            count++;
        }

        // Bank-specific metrics
        if (companyType == CompanyType.BANK || companyType == CompanyType.NBFC) {
            if (metrics.containsKey("nii") && !metrics.get("nii").isEmpty()) {
                result.setNii(metrics.get("nii").get(0));
                count++;
            }
            if (metrics.containsKey("provisions") && !metrics.get("provisions").isEmpty()) {
                result.setProvisions(metrics.get("provisions").get(0));
                count++;
            }
        }

        // Calculate EBITDA if not directly available
        if (result.getEbitda() == null && result.getPbt() != null) {
            Double ebitda = result.getPbt();
            if (result.getDepreciation() != null) ebitda += result.getDepreciation();
            if (result.getInterestCost() != null) ebitda += result.getInterestCost();
            result.setEbitda(ebitda);
        }

        return count;
    }

    /**
     * Calculate QoQ and YoY growth rates.
     */
    @Transactional
    public void calculateGrowthRates(QuarterlyResult current) {
        String ticker = current.getTicker();
        String quarter = current.getQuarter();
        Integer fiscalYear = current.getFiscalYear();

        // Find previous quarter
        Optional<QuarterlyResult> prevQuarter = repository.findPreviousQuarter(ticker, fiscalYear, quarter);
        if (prevQuarter.isPresent()) {
            QuarterlyResult prev = prevQuarter.get();
            current.setRevenueQoQ(calculateGrowth(current.getRevenue(), prev.getRevenue()));
            current.setEbitdaQoQ(calculateGrowth(current.getEbitda(), prev.getEbitda()));
            current.setPatQoQ(calculateGrowth(current.getPat(), prev.getPat()));
            current.setEpsQoQ(calculateGrowth(current.getEpsBasic(), prev.getEpsBasic()));
            current.setEbitdaMarginQoQPp(calculatePpChange(current.getEbitdaMargin(), prev.getEbitdaMargin()));
            current.setPatMarginQoQPp(calculatePpChange(current.getPatMargin(), prev.getPatMargin()));

            // Bank metrics
            if (current.isFinancialCompany()) {
                current.setNiiQoQ(calculateGrowth(current.getNii(), prev.getNii()));
                current.setNimQoQPp(calculatePpChange(current.getNim(), prev.getNim()));
            }
        }

        // Find same quarter last year
        Optional<QuarterlyResult> lastYear = repository.findByTickerAndQuarterAndFiscalYearLessThan(
                ticker, quarter, fiscalYear);
        if (lastYear.isPresent()) {
            QuarterlyResult prev = lastYear.get();
            current.setRevenueYoY(calculateGrowth(current.getRevenue(), prev.getRevenue()));
            current.setEbitdaYoY(calculateGrowth(current.getEbitda(), prev.getEbitda()));
            current.setPatYoY(calculateGrowth(current.getPat(), prev.getPat()));
            current.setEpsYoY(calculateGrowth(current.getEpsBasic(), prev.getEpsBasic()));
            current.setEbitdaMarginYoYPp(calculatePpChange(current.getEbitdaMargin(), prev.getEbitdaMargin()));
            current.setPatMarginYoYPp(calculatePpChange(current.getPatMargin(), prev.getPatMargin()));

            // Bank metrics
            if (current.isFinancialCompany()) {
                current.setNiiYoY(calculateGrowth(current.getNii(), prev.getNii()));
                current.setNimYoYPp(calculatePpChange(current.getNim(), prev.getNim()));
            }
        }

        repository.save(current);
    }

    private Double calculateGrowth(Double current, Double previous) {
        if (current == null || previous == null || previous == 0) return null;
        return ((current - previous) / Math.abs(previous)) * 100;
    }

    private Double calculatePpChange(Double current, Double previous) {
        if (current == null || previous == null) return null;
        return current - previous;
    }

    /**
     * Detect company type based on ticker or PDF content.
     */
    private CompanyType detectCompanyType(String ticker, String pdfText) {
        String upperTicker = ticker.toUpperCase();

        if (BANK_TICKERS.contains(upperTicker)) {
            return CompanyType.BANK;
        }
        if (NBFC_TICKERS.contains(upperTicker)) {
            return CompanyType.NBFC;
        }
        if (pdfParserService.isBankPdf(pdfText)) {
            return CompanyType.BANK;
        }

        return CompanyType.REGULAR;
    }

    /**
     * Clean ticker (remove NSE: prefix).
     */
    private String cleanTicker(String ticker) {
        if (ticker == null) return null;
        ticker = ticker.trim().toUpperCase();
        if (ticker.startsWith("NSE:")) {
            return ticker.substring(4);
        }
        if (ticker.startsWith("BSE:")) {
            return ticker.substring(4);
        }
        return ticker;
    }

    /**
     * Create a failed result record.
     */
    private QuarterlyResult createFailedResult(String ticker, String pdfUrl, Long announcementId, String reason) {
        QuarterlyResult result = new QuarterlyResult();
        result.setTicker(ticker);
        result.setQuarter("Q3"); // Default
        result.setFiscalYear(LocalDateTime.now().getYear());
        result.setPdfUrl(pdfUrl);
        result.setAnnouncementId(announcementId);
        result.setParsedAt(LocalDateTime.now());
        result.setParseStatus(ParseStatus.FAILED);
        result.setRemarks(reason);
        return repository.save(result);
    }

    // ===== Query Methods =====

    /**
     * Get all results for a ticker (ordered by most recent first).
     */
    public List<QuarterlyResult> getResultsForTicker(String ticker) {
        return repository.findByTickerOrderByQuarterDesc(cleanTicker(ticker));
    }

    /**
     * Get latest result for a ticker.
     */
    public Optional<QuarterlyResult> getLatestResult(String ticker) {
        return repository.findLatestByTicker(cleanTicker(ticker));
    }

    /**
     * Get latest results for multiple tickers (for portfolio view).
     */
    public List<QuarterlyResult> getLatestResultsForTickers(List<String> tickers) {
        List<String> cleanedTickers = tickers.stream()
                .map(this::cleanTicker)
                .toList();
        return repository.findLatestByTickerIn(cleanedTickers);
    }

    /**
     * Get all results for multiple tickers.
     */
    public List<QuarterlyResult> getResultsForTickers(List<String> tickers) {
        List<String> cleanedTickers = tickers.stream()
                .map(this::cleanTicker)
                .toList();
        return repository.findByTickerIn(cleanedTickers);
    }

    /**
     * Manually save/update a result.
     */
    @Transactional
    public QuarterlyResult saveResult(QuarterlyResult result) {
        result.calculateMargins();
        result.setParseStatus(ParseStatus.MANUAL);
        QuarterlyResult saved = repository.save(result);
        calculateGrowthRates(saved);
        return saved;
    }

    /**
     * Delete a result.
     */
    @Transactional
    public void deleteResult(Long id) {
        repository.deleteById(id);
    }
}
