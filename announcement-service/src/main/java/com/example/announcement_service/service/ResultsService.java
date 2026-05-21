package com.example.announcement_service.service;

import com.example.announcement_service.model.Announcement;
import com.example.announcement_service.model.ParsedResult;
import com.example.announcement_service.repository.AnnouncementRepository;
import com.example.announcement_service.repository.ParsedResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service to manage quarterly financial results.
 * Parses results from announcement PDFs and stores them in parsed_results table.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResultsService {

    private final ParsedResultRepository parsedResultRepository;
    private final AnnouncementRepository announcementRepository;
    private final PdfParserService pdfParserService;
    private final TickerMappingService tickerMappingService;
    private final ScreenerScraperService screenerScraperService;

    // Keywords to identify financial result announcements
    private static final String[] FINANCIAL_RESULT_KEYWORDS = {
            "financial result",
            "quarterly result",
            "un-audited financial",
            "unaudited financial",
            "audited financial",
            "standalone financial",
            "consolidated financial",
            "outcome of board meeting",
            "outcome of the board meeting",
            "board meeting outcome",
            "board meeting",  // Catches "Board Meeting NEWS" etc.
            "results for the quarter",
            "results for quarter"
    };

    /**
     * Get all parsed results for a ticker.
     * Uses PDF parsing as primary source, falls back to Screener.in if PDF fails.
     */
    @Transactional
    public Map<String, Object> getResultsForTicker(String ticker) {
        String nseTicker = tickerMappingService.extractNseTicker(ticker);
        if (nseTicker == null) {
            nseTicker = ticker.toUpperCase();
        }

        log.info("Getting results for ticker: {}", nseTicker);

        // First check if we have cached parsed results
        List<ParsedResult> results = parsedResultRepository.findByTickerOrderByQuarterDesc(nseTicker);
        String source = "cache";

        // Check if results are stale (older than 24 hours)
        boolean resultsStale = results.isEmpty() ||
                (results.get(0).getParsedAt() != null &&
                 results.get(0).getParsedAt().isBefore(LocalDateTime.now().minusHours(24)));

        // If no results or results are stale, try PDF parsing first (more accurate)
        if (resultsStale) {
            log.info("No cached results or stale for {}, trying PDF parsing first", nseTicker);
            List<ParsedResult> pdfResults = parseResultsFromAnnouncements(nseTicker);

            if (!pdfResults.isEmpty()) {
                results = pdfResults;
                source = "pdf";
            } else {
                // If PDF parsing fails, fall back to Screener
                log.info("PDF parsing returned no results for {}, trying Screener.in as fallback", nseTicker);
                List<ParsedResult> screenerResults = fetchFromScreener(nseTicker);
                if (!screenerResults.isEmpty()) {
                    results = screenerResults;
                    source = "screener";
                }
            }
        } else if (!results.isEmpty()) {
            source = results.get(0).getRemarks() != null &&
                     results.get(0).getRemarks().contains("Screener") ? "screener" : "pdf";
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ticker", nseTicker);
        response.put("companyType", results.isEmpty() ? "REGULAR" : results.get(0).getCompanyType().name());
        response.put("results", results);
        response.put("resultCount", results.size());
        response.put("source", results.isEmpty() ? "none" : source);

        return response;
    }

    /**
     * Get results for multiple tickers (portfolio view).
     */
    @Transactional
    public Map<String, Object> getResultsForTickers(List<String> tickers) {
        Map<String, List<ParsedResult>> resultsByTicker = new LinkedHashMap<>();

        for (String ticker : tickers) {
            String nseTicker = tickerMappingService.extractNseTicker(ticker);
            if (nseTicker == null) {
                nseTicker = ticker.toUpperCase();
            }

            List<ParsedResult> results = parsedResultRepository.findByTickerOrderByQuarterDesc(nseTicker);

            // If no results, try to parse from announcements
            if (results.isEmpty()) {
                results = parseResultsFromAnnouncements(nseTicker);
            }

            if (!results.isEmpty()) {
                resultsByTicker.put(nseTicker, results);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("resultsByTicker", resultsByTicker);
        response.put("tickerCount", resultsByTicker.size());

        return response;
    }

    /**
     * Parse results from announcements for a ticker.
     * Finds financial result PDFs and parses them.
     */
    @Transactional
    public List<ParsedResult> parseResultsFromAnnouncements(String nseTicker) {
        List<ParsedResult> parsedResults = new ArrayList<>();

        // Find financial result announcements for this ticker
        LocalDateTime afterDate = LocalDateTime.now().minusDays(365); // Last year
        List<Announcement> announcements = announcementRepository.findByNseTickersInAndAfterDate(
                List.of(nseTicker), afterDate);

        // Also try searching by company name
        if (announcements.isEmpty()) {
            announcements = announcementRepository.findByCompanyNameContainingAndAfterDate(nseTicker, afterDate);
        }

        // Filter for financial result announcements
        List<Announcement> resultAnnouncements = announcements.stream()
                .filter(this::isFinancialResultAnnouncement)
                .filter(a -> a.getPdfUrl() != null && !a.getPdfUrl().isBlank())
                .sorted(Comparator.comparing(Announcement::getAnnouncementDate).reversed())
                .limit(4) // Last 4 quarters
                .toList();

        log.info("Found {} financial result announcements for {}", resultAnnouncements.size(), nseTicker);

        for (Announcement ann : resultAnnouncements) {
            // Skip if already parsed
            if (parsedResultRepository.existsByAnnouncementId(ann.getId())) {
                Optional<ParsedResult> existing = parsedResultRepository.findByAnnouncementId(ann.getId());
                existing.ifPresent(parsedResults::add);
                continue;
            }

            // Parse the PDF
            ParsedResult result = parseAnnouncementPdf(ann, nseTicker);
            if (result != null) {
                parsedResults.add(result);
            }
        }

        // Sort by quarter descending
        parsedResults.sort((a, b) -> {
            int yearCmp = b.getFiscalYear().compareTo(a.getFiscalYear());
            if (yearCmp != 0) return yearCmp;
            return getQuarterOrder(b.getQuarter()) - getQuarterOrder(a.getQuarter());
        });

        // Calculate QoQ and YoY changes
        calculateGrowthMetrics(parsedResults);

        return parsedResults;
    }

    /**
     * Parse a specific announcement PDF and save the result.
     */
    @Transactional
    public ParsedResult parseAnnouncementPdf(Announcement announcement, String nseTicker) {
        if (announcement == null || announcement.getPdfUrl() == null) {
            return null;
        }

        log.info("Parsing PDF for {} from: {}", nseTicker, announcement.getPdfUrl());

        try {
            // Download and extract text
            String pdfText = pdfParserService.downloadAndExtractText(announcement.getPdfUrl());
            if (pdfText == null || pdfText.isEmpty()) {
                log.warn("Could not extract text from PDF");
                return null;
            }

            // Check if PDF is likely scanned (very little text extracted)
            if (pdfText.length() < 5000) {
                log.warn("PDF for {} appears to be scanned ({} chars). PDF parsing may fail, Screener fallback recommended.",
                        nseTicker, pdfText.length());
            }

            log.info("PDF text length: {} chars. Preview: {}", pdfText.length(),
                    pdfText.substring(0, Math.min(300, pdfText.length())).replaceAll("\\s+", " "));

            // Extract consolidated section
            String consolidatedText = pdfParserService.extractConsolidatedSection(pdfText);
            log.info("Consolidated section length: {} chars", consolidatedText != null ? consolidatedText.length() : 0);

            // Parse metrics
            Map<String, List<Double>> metrics = pdfParserService.parseMetrics(consolidatedText);
            log.info("Parsed {} metrics: {}", metrics.size(), metrics);

            // Extract quarter info
            String[] quarterInfo = pdfParserService.extractQuarterInfo(pdfText);

            // Detect company type
            boolean isBank = pdfParserService.isBankPdf(pdfText);

            // Build result entity
            ParsedResult result = ParsedResult.builder()
                    .ticker(nseTicker)
                    .quarter(quarterInfo != null ? quarterInfo[0] : "Q3")
                    .fiscalYear(quarterInfo != null ? Integer.parseInt(quarterInfo[1]) : 2025)
                    .companyType(isBank ? ParsedResult.CompanyType.BANK : ParsedResult.CompanyType.REGULAR)
                    .announcementId(announcement.getId())
                    .pdfUrl(announcement.getPdfUrl())
                    .resultDate(announcement.getAnnouncementDate())
                    .parsedAt(LocalDateTime.now())
                    .build();

            // Set metrics
            setMetricsFromParsed(result, metrics);

            // Calculate margins
            result.calculateMargins();

            // Determine parse status
            if (metrics.isEmpty()) {
                result.setParseStatus(ParsedResult.ParseStatus.FAILED);
                result.setRemarks("Could not parse any metrics from PDF");
            } else if (result.getRevenue() != null && result.getPat() != null) {
                result.setParseStatus(ParsedResult.ParseStatus.SUCCESS);
            } else {
                result.setParseStatus(ParsedResult.ParseStatus.PARTIAL);
                result.setRemarks("Some metrics could not be parsed");
            }

            // Save to DB (handle unique constraint)
            try {
                Optional<ParsedResult> existing = parsedResultRepository
                        .findByTickerIgnoreCaseAndQuarterAndFiscalYear(
                                nseTicker, result.getQuarter(), result.getFiscalYear());

                if (existing.isPresent()) {
                    // Update existing
                    ParsedResult ex = existing.get();
                    copyMetrics(result, ex);
                    result = parsedResultRepository.save(ex);
                } else {
                    result = parsedResultRepository.save(result);
                }

                log.info("Saved parsed result for {} {}: revenue={}, pat={}",
                        nseTicker, result.getQuarterLabel(), result.getRevenue(), result.getPat());

                return result;

            } catch (Exception e) {
                log.warn("Could not save result for {}: {}", nseTicker, e.getMessage());
                return null;
            }

        } catch (Exception e) {
            log.error("Error parsing PDF for {}: {}", nseTicker, e.getMessage());
            return null;
        }
    }

    /**
     * Manually trigger parsing for a specific ticker and PDF URL.
     */
    @Transactional
    public ParsedResult triggerParsing(String ticker, String pdfUrl, Long announcementId) {
        String nseTicker = tickerMappingService.extractNseTicker(ticker);
        if (nseTicker == null) {
            nseTicker = ticker.toUpperCase();
        }

        log.info("Manual parsing triggered for ticker: {}, pdfUrl: {}", nseTicker, pdfUrl);

        // Create a temporary announcement object
        Announcement ann = Announcement.builder()
                .id(announcementId)
                .pdfUrl(pdfUrl)
                .nseTicker(nseTicker)
                .announcementDate(LocalDateTime.now())
                .build();

        return parseAnnouncementPdf(ann, nseTicker);
    }

    /**
     * Check if an announcement is a financial result.
     */
    private boolean isFinancialResultAnnouncement(Announcement ann) {
        String subject = ann.getSubject() != null ? ann.getSubject().toLowerCase() : "";
        String category = ann.getCategory() != null ? ann.getCategory().toLowerCase() : "";

        // Direct category match - "Result" category is always a financial result
        if (category.equals("result")) {
            return true;
        }

        for (String keyword : FINANCIAL_RESULT_KEYWORDS) {
            if (subject.contains(keyword) || category.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Set metrics on result from parsed values.
     */
    private void setMetricsFromParsed(ParsedResult result, Map<String, List<Double>> metrics) {
        // Take first value (current quarter) from each metric
        if (metrics.containsKey("revenue")) {
            result.setRevenue(metrics.get("revenue").get(0));
        }
        if (metrics.containsKey("otherIncome")) {
            result.setOtherIncome(metrics.get("otherIncome").get(0));
        }
        if (metrics.containsKey("totalIncome")) {
            result.setTotalIncome(metrics.get("totalIncome").get(0));
        }
        if (metrics.containsKey("totalExpenses")) {
            result.setTotalExpenses(metrics.get("totalExpenses").get(0));
        }
        if (metrics.containsKey("pbt")) {
            result.setPbt(metrics.get("pbt").get(0));
        }
        if (metrics.containsKey("tax")) {
            result.setTax(metrics.get("tax").get(0));
        }
        if (metrics.containsKey("pat")) {
            result.setPat(metrics.get("pat").get(0));
        }
        if (metrics.containsKey("epsBasic")) {
            result.setEpsBasic(metrics.get("epsBasic").get(0));
        }
        if (metrics.containsKey("epsDiluted")) {
            result.setEpsDiluted(metrics.get("epsDiluted").get(0));
        }
        if (metrics.containsKey("nii")) {
            result.setNii(metrics.get("nii").get(0));
        }
        if (metrics.containsKey("provisions")) {
            result.setProvisions(metrics.get("provisions").get(0));
        }

        // Calculate EBITDA if not directly available
        if (result.getTotalIncome() != null && result.getTotalExpenses() != null && result.getEbitda() == null) {
            result.setEbitda(result.getTotalIncome() - result.getTotalExpenses());
        }
    }

    /**
     * Copy metrics from source to target result.
     */
    private void copyMetrics(ParsedResult source, ParsedResult target) {
        target.setRevenue(source.getRevenue());
        target.setOtherIncome(source.getOtherIncome());
        target.setTotalIncome(source.getTotalIncome());
        target.setTotalExpenses(source.getTotalExpenses());
        target.setEbitda(source.getEbitda());
        target.setPbt(source.getPbt());
        target.setTax(source.getTax());
        target.setPat(source.getPat());
        target.setEpsBasic(source.getEpsBasic());
        target.setEpsDiluted(source.getEpsDiluted());
        target.setNii(source.getNii());
        target.setProvisions(source.getProvisions());
        target.setEbitdaMargin(source.getEbitdaMargin());
        target.setPatMargin(source.getPatMargin());
        target.setCompanyType(source.getCompanyType());
        target.setParseStatus(source.getParseStatus());
        target.setRemarks(source.getRemarks());
        target.setParsedAt(source.getParsedAt());
    }

    /**
     * Calculate QoQ and YoY growth metrics.
     */
    private void calculateGrowthMetrics(List<ParsedResult> results) {
        if (results.size() < 2) return;

        for (int i = 0; i < results.size(); i++) {
            ParsedResult current = results.get(i);

            // Find previous quarter (i+1 since sorted desc)
            if (i + 1 < results.size()) {
                ParsedResult prev = results.get(i + 1);
                calculateQoQ(current, prev);
            }

            // Find same quarter last year
            for (int j = i + 1; j < results.size(); j++) {
                ParsedResult other = results.get(j);
                if (other.getQuarter().equals(current.getQuarter()) &&
                    other.getFiscalYear() == current.getFiscalYear() - 1) {
                    calculateYoY(current, other);
                    break;
                }
            }

            // Save updated metrics
            parsedResultRepository.save(current);
        }
    }

    private void calculateQoQ(ParsedResult current, ParsedResult prev) {
        if (prev.getRevenue() != null && prev.getRevenue() != 0 && current.getRevenue() != null) {
            current.setRevenueQoQ(((current.getRevenue() - prev.getRevenue()) / Math.abs(prev.getRevenue())) * 100);
        }
        if (prev.getEbitda() != null && prev.getEbitda() != 0 && current.getEbitda() != null) {
            current.setEbitdaQoQ(((current.getEbitda() - prev.getEbitda()) / Math.abs(prev.getEbitda())) * 100);
        }
        if (prev.getPat() != null && prev.getPat() != 0 && current.getPat() != null) {
            current.setPatQoQ(((current.getPat() - prev.getPat()) / Math.abs(prev.getPat())) * 100);
        }
        if (prev.getEpsBasic() != null && prev.getEpsBasic() != 0 && current.getEpsBasic() != null) {
            current.setEpsQoQ(((current.getEpsBasic() - prev.getEpsBasic()) / Math.abs(prev.getEpsBasic())) * 100);
        }
        if (prev.getEbitdaMargin() != null && current.getEbitdaMargin() != null) {
            current.setEbitdaMarginQoQPp(current.getEbitdaMargin() - prev.getEbitdaMargin());
        }
        if (prev.getPatMargin() != null && current.getPatMargin() != null) {
            current.setPatMarginQoQPp(current.getPatMargin() - prev.getPatMargin());
        }
        if (prev.getNii() != null && prev.getNii() != 0 && current.getNii() != null) {
            current.setNiiQoQ(((current.getNii() - prev.getNii()) / Math.abs(prev.getNii())) * 100);
        }
    }

    private void calculateYoY(ParsedResult current, ParsedResult yoy) {
        if (yoy.getRevenue() != null && yoy.getRevenue() != 0 && current.getRevenue() != null) {
            current.setRevenueYoY(((current.getRevenue() - yoy.getRevenue()) / Math.abs(yoy.getRevenue())) * 100);
        }
        if (yoy.getEbitda() != null && yoy.getEbitda() != 0 && current.getEbitda() != null) {
            current.setEbitdaYoY(((current.getEbitda() - yoy.getEbitda()) / Math.abs(yoy.getEbitda())) * 100);
        }
        if (yoy.getPat() != null && yoy.getPat() != 0 && current.getPat() != null) {
            current.setPatYoY(((current.getPat() - yoy.getPat()) / Math.abs(yoy.getPat())) * 100);
        }
        if (yoy.getEpsBasic() != null && yoy.getEpsBasic() != 0 && current.getEpsBasic() != null) {
            current.setEpsYoY(((current.getEpsBasic() - yoy.getEpsBasic()) / Math.abs(yoy.getEpsBasic())) * 100);
        }
        if (yoy.getEbitdaMargin() != null && current.getEbitdaMargin() != null) {
            current.setEbitdaMarginYoYPp(current.getEbitdaMargin() - yoy.getEbitdaMargin());
        }
        if (yoy.getPatMargin() != null && current.getPatMargin() != null) {
            current.setPatMarginYoYPp(current.getPatMargin() - yoy.getPatMargin());
        }
        if (yoy.getNii() != null && yoy.getNii() != 0 && current.getNii() != null) {
            current.setNiiYoY(((current.getNii() - yoy.getNii()) / Math.abs(yoy.getNii())) * 100);
        }
    }

    private int getQuarterOrder(String quarter) {
        return switch (quarter) {
            case "Q1" -> 1;
            case "Q2" -> 2;
            case "Q3" -> 3;
            case "Q4" -> 4;
            default -> 0;
        };
    }

    /**
     * Fetch quarterly results from Screener.in.
     * Saves results to database and returns them.
     */
    @Transactional
    public List<ParsedResult> fetchFromScreener(String nseTicker) {
        try {
            List<ParsedResult> screenerResults = screenerScraperService.fetchQuarterlyResults(nseTicker);

            if (screenerResults.isEmpty()) {
                log.info("No results from Screener for {}", nseTicker);
                return Collections.emptyList();
            }

            log.info("Fetched {} results from Screener for {}", screenerResults.size(), nseTicker);

            // Save results to database
            List<ParsedResult> savedResults = new ArrayList<>();
            for (ParsedResult result : screenerResults) {
                try {
                    // Check if we already have this quarter
                    Optional<ParsedResult> existing = parsedResultRepository
                            .findByTickerIgnoreCaseAndQuarterAndFiscalYear(
                                    nseTicker, result.getQuarter(), result.getFiscalYear());

                    if (existing.isPresent()) {
                        // Update existing
                        ParsedResult ex = existing.get();
                        copyMetrics(result, ex);
                        ex.setRemarks("Scraped from Screener.in");
                        savedResults.add(parsedResultRepository.save(ex));
                    } else {
                        savedResults.add(parsedResultRepository.save(result));
                    }
                } catch (Exception e) {
                    log.warn("Could not save Screener result for {} {}: {}",
                            nseTicker, result.getQuarterLabel(), e.getMessage());
                }
            }

            // Calculate growth metrics
            calculateGrowthMetrics(savedResults);

            return savedResults;

        } catch (Exception e) {
            log.error("Error fetching from Screener for {}: {}", nseTicker, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Force refresh results - PDF first (primary), Screener as fallback.
     * Bypasses cache completely.
     */
    @Transactional
    public Map<String, Object> forceRefreshResults(String ticker) {
        String nseTicker = tickerMappingService.extractNseTicker(ticker);
        if (nseTicker == null) {
            nseTicker = ticker.toUpperCase();
        }

        log.info("Force refreshing results for: {} (PDF first, Screener fallback)", nseTicker);

        String source = "none";
        List<ParsedResult> results;

        // Try PDF parsing first (primary source)
        log.info("Trying PDF parsing for {}", nseTicker);
        results = parseResultsFromAnnouncements(nseTicker);

        if (!results.isEmpty()) {
            source = "pdf";
            log.info("Successfully parsed {} results from PDF for {}", results.size(), nseTicker);
        } else {
            // Fall back to Screener
            log.info("PDF parsing returned no results for {}, trying Screener as fallback", nseTicker);
            results = fetchFromScreener(nseTicker);
            if (!results.isEmpty()) {
                source = "screener";
                log.info("Successfully fetched {} results from Screener for {}", results.size(), nseTicker);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ticker", nseTicker);
        response.put("source", source);
        response.put("companyType", results.isEmpty() ? "REGULAR" : results.get(0).getCompanyType().name());
        response.put("results", results);
        response.put("resultCount", results.size());

        return response;
    }

    /**
     * Force refresh results from Screener.in only (bypasses cache).
     */
    @Transactional
    public Map<String, Object> refreshFromScreener(String ticker) {
        String nseTicker = tickerMappingService.extractNseTicker(ticker);
        if (nseTicker == null) {
            nseTicker = ticker.toUpperCase();
        }

        log.info("Force refreshing from Screener for: {}", nseTicker);

        List<ParsedResult> results = fetchFromScreener(nseTicker);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ticker", nseTicker);
        response.put("source", "screener");
        response.put("results", results);
        response.put("resultCount", results.size());

        return response;
    }
}
