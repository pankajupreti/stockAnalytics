package com.example.announcement_service.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service to download and parse quarterly results from PDFs.
 * Extracts consolidated financial data from BSE/NSE result PDFs.
 * Automatically falls back to OCR for scanned documents.
 *
 * PDF Structure (typical Indian quarterly results):
 * - Page has a table with columns: Particulars | Quarter ended (multiple) | Nine months ended | Year ended
 * - Column headers have dates like 31.12.2025, 30.09.2025, 31.12.2024
 * - Rows: Revenue from operations, Other Income, Total Income, Total Expenses,
 *         Profit before tax, Tax expense, Profit after tax, EPS Basic, EPS Diluted
 */
@Service
public class PdfParserService {

    private static final Logger log = LoggerFactory.getLogger(PdfParserService.class);

    @Autowired(required = false)
    private OcrPdfParserService ocrPdfParserService;

    @Value("${pdf.download.timeout-seconds:30}")
    private int downloadTimeoutSeconds = 30;

    @Value("${pdf.parse.max-pages:15}")
    private int maxPages = 15;

    @Value("${pdf.ocr.enabled:true}")
    private boolean ocrEnabled = true;

    // Minimum text length to consider PDF as text-based (not scanned)
    private static final int MIN_TEXT_LENGTH = 5000;

    /**
     * Download PDF from URL and extract text.
     * Automatically uses OCR if the PDF appears to be scanned.
     */
    public String downloadAndExtractText(String pdfUrl) {
        try {
            log.info("Downloading PDF from: {}", pdfUrl);

            URL url = new URL(pdfUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(downloadTimeoutSeconds * 1000);
            conn.setReadTimeout(downloadTimeoutSeconds * 1000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "application/pdf");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log.error("Failed to download PDF: HTTP {}", responseCode);
                return null;
            }

            // First try regular text extraction
            String text;
            try (InputStream inputStream = conn.getInputStream();
                 PDDocument document = PDDocument.load(inputStream)) {
                text = extractTextFromDocument(document);
            }

            // Check if PDF is likely scanned
            if (text != null && text.length() < MIN_TEXT_LENGTH) {
                log.info("PDF appears to be scanned ({} chars). Attempting OCR...", text.length());

                // Try OCR if available
                if (ocrEnabled && ocrPdfParserService != null && ocrPdfParserService.isOcrAvailable()) {
                    String ocrText = ocrPdfParserService.extractTextWithOcrFromUrl(pdfUrl);
                    if (ocrText != null && ocrText.length() > text.length()) {
                        log.info("OCR extracted {} chars (vs {} from text)", ocrText.length(), text.length());
                        return ocrText;
                    }
                } else {
                    log.warn("OCR not available for scanned PDF. Install Tesseract for OCR support.");
                }
            }

            return text;

        } catch (Exception e) {
            log.error("Error downloading/parsing PDF from {}: {}", pdfUrl, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Extract text from a local PDF file.
     * Automatically uses OCR if the PDF appears to be scanned.
     */
    public String extractTextFromFile(String filePath) {
        try {
            log.info("Reading PDF from file: {}", filePath);

            // First try regular text extraction
            String text;
            try (PDDocument document = PDDocument.load(new File(filePath))) {
                text = extractTextFromDocument(document);
            }

            // Check if PDF is likely scanned (very little text)
            if (text != null && text.length() < MIN_TEXT_LENGTH) {
                log.info("PDF appears to be scanned ({} chars). Attempting OCR...", text.length());

                // Try OCR if available
                if (ocrEnabled && ocrPdfParserService != null && ocrPdfParserService.isOcrAvailable()) {
                    String ocrText = ocrPdfParserService.extractTextWithOcr(filePath);
                    if (ocrText != null && ocrText.length() > text.length()) {
                        log.info("OCR extracted {} chars (vs {} from text)", ocrText.length(), text.length());
                        return ocrText;
                    }
                } else {
                    log.warn("OCR not available. Install Tesseract for scanned PDF support.");
                }
            }

            return text;

        } catch (Exception e) {
            log.error("Error reading PDF from {}: {}", filePath, e.getMessage(), e);
            return null;
        }
    }

    private String extractTextFromDocument(PDDocument document) throws Exception {
        int numPages = Math.min(document.getNumberOfPages(), maxPages);
        log.info("PDF has {} pages, extracting up to {} pages", document.getNumberOfPages(), numPages);

        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(1);
        stripper.setEndPage(numPages);
        stripper.setSortByPosition(true);

        String text = stripper.getText(document);
        log.info("Extracted {} characters from PDF", text.length());

        return text;
    }

    /**
     * Extract the consolidated financial results section from PDF text.
     * Looks for "Consolidated" section, falls back to full text.
     */
    public String extractConsolidatedSection(String fullText) {
        if (fullText == null || fullText.isEmpty()) {
            return null;
        }

        String upperText = fullText.toUpperCase();

        // Find consolidated section
        int consolidatedStart = upperText.indexOf("CONSOLIDATED");
        if (consolidatedStart < 0) {
            consolidatedStart = upperText.indexOf("UNAUDITED");
        }
        if (consolidatedStart < 0) {
            consolidatedStart = upperText.indexOf("FINANCIAL RESULTS");
        }

        if (consolidatedStart >= 0) {
            // Find end of section (next major section or notes)
            int sectionEnd = findSectionEnd(upperText, consolidatedStart);
            String section = fullText.substring(consolidatedStart, sectionEnd);
            log.info("Extracted consolidated section: {} chars", section.length());
            return section;
        }

        log.warn("Could not find consolidated section, using full text");
        return fullText;
    }

    private int findSectionEnd(String upperText, int start) {
        String[] endMarkers = {"NOTES TO THE", "NOTES:", "FOR AND ON BEHALF", "STANDALONE",
                               "INDEPENDENT AUDITOR", "REVIEW REPORT"};

        int minEnd = upperText.length();
        for (String marker : endMarkers) {
            int idx = upperText.indexOf(marker, start + 500);
            if (idx > start && idx < minEnd) {
                minEnd = idx;
            }
        }

        // Limit to reasonable size
        return Math.min(minEnd, start + 10000);
    }

    /**
     * Parse financial metrics from PDF text.
     * This is the main parsing method that extracts quarterly data.
     *
     * Returns a map where:
     * - Key: metric name (revenue, pat, pbt, eps, etc.)
     * - Value: list of values for each quarter column (first = current quarter)
     */
    public Map<String, List<Double>> parseMetrics(String text) {
        Map<String, List<Double>> results = new LinkedHashMap<>();

        if (text == null || text.isEmpty()) {
            return results;
        }

        String[] lines = text.split("\\r?\\n");
        log.info("Parsing {} lines of text", lines.length);

        // Parse each metric by finding its row - order matters for disambiguation
        // Revenue: look for "(a) Revenue from operations" pattern specifically
        results.put("revenue", findMetricValuesStrict(lines,
            new String[]{"(a) revenue from operations", "revenue from operations"},
            new String[]{"total income"})); // exclude patterns

        results.put("otherIncome", findMetricValuesStrict(lines,
            new String[]{"(b) other income", "other income"},
            new String[]{"comprehensive"}));

        results.put("totalIncome", findMetricValuesStrict(lines,
            new String[]{"total income"},
            new String[]{"comprehensive"}));

        results.put("totalExpenses", findMetricValuesStrict(lines,
            new String[]{"total expenses", "total expenditure"},
            new String[]{}));

        // For profit metrics, be more specific to avoid confusion
        results.put("pbt", findMetricValuesStrict(lines,
            new String[]{"profit before tax (3+4)", "profit before tax", "profit before exceptional"},
            new String[]{"share of profit", "attributable"}));

        results.put("tax", findMetricValuesStrict(lines,
            new String[]{"tax expenses", "tax expense"},
            new String[]{"deferred", "current"}));

        // PAT - look for "Profit for the period/year" row
        results.put("pat", findMetricValuesStrict(lines,
            new String[]{"profit for the period/year", "profit for the period", "net profit after tax"},
            new String[]{"share of profit", "attributable", "comprehensive", "before"}));

        // EPS - look specifically for "- Basic" or "Basic (Rs.)" patterns
        results.put("epsBasic", findEpsValues(lines, "basic"));
        results.put("epsDiluted", findEpsValues(lines, "diluted"));

        // Log what we found
        log.info("Parsed metrics: revenue={}, pat={}, pbt={}, epsBasic={}",
                results.get("revenue"),
                results.get("pat"),
                results.get("pbt"),
                results.get("epsBasic"));

        // Remove empty entries
        results.entrySet().removeIf(e -> e.getValue().isEmpty());

        return results;
    }

    /**
     * Find metric values with strict pattern matching and exclusion patterns.
     */
    private List<Double> findMetricValuesStrict(String[] lines, String[] includePatterns, String[] excludePatterns) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            String lowerLine = line.toLowerCase();

            // Check if line matches any include pattern
            boolean matches = false;
            for (String pattern : includePatterns) {
                if (lowerLine.contains(pattern.toLowerCase())) {
                    matches = true;
                    break;
                }
            }

            if (!matches) continue;

            // Check if line matches any exclude pattern
            boolean excluded = false;
            for (String exclude : excludePatterns) {
                if (!exclude.isEmpty() && lowerLine.contains(exclude.toLowerCase())) {
                    excluded = true;
                    break;
                }
            }

            if (excluded) continue;

            // Extract numbers from this line
            List<Double> values = extractNumbersFromLine(line);

            // If not enough values, try combining with next lines
            if (values.size() < 2 && i + 1 < lines.length) {
                String combinedLine = line + " " + lines[i + 1];
                values = extractNumbersFromLine(combinedLine);
            }
            if (values.size() < 2 && i + 2 < lines.length) {
                String combinedLine = line + " " + lines[i + 1] + " " + lines[i + 2];
                values = extractNumbersFromLine(combinedLine);
            }

            if (!values.isEmpty()) {
                log.debug("Found metric in line: {} -> values: {}",
                        line.substring(0, Math.min(60, line.length())), values);
                return values;
            }
        }
        return new ArrayList<>();
    }

    /**
     * Find EPS values specifically - looks for "- Basic" or "- Diluted" rows.
     */
    private List<Double> findEpsValues(String[] lines, String epsType) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            String lowerLine = line.toLowerCase();

            // Look for patterns like "- Basic (Rs.)" or "- Basic" or "Basic (Rs.)"
            boolean isEpsLine = (lowerLine.contains("- " + epsType) ||
                                 lowerLine.contains("-" + epsType) ||
                                 (lowerLine.contains(epsType) && lowerLine.contains("rs")));

            // Also match "earnings per equity share" section
            if (!isEpsLine && lowerLine.contains(epsType) &&
                (lowerLine.contains("annualised") || lowerLine.contains("per share") ||
                 lowerLine.contains("per equity"))) {
                isEpsLine = true;
            }

            if (!isEpsLine) continue;

            // Skip if it's part of another metric (like "basic salary")
            if (lowerLine.contains("salary") || lowerLine.contains("employee")) continue;

            // Extract numbers
            List<Double> values = extractNumbersFromLine(line);

            // If not enough values, try next line
            if (values.size() < 2 && i + 1 < lines.length) {
                String combinedLine = line + " " + lines[i + 1];
                values = extractNumbersFromLine(combinedLine);
            }

            // EPS values are typically small (under 100), filter out large numbers
            List<Double> epsValues = new ArrayList<>();
            for (Double v : values) {
                if (Math.abs(v) < 1000) { // EPS is typically under 1000
                    epsValues.add(v);
                }
            }

            if (!epsValues.isEmpty()) {
                log.debug("Found EPS {} in line: {} -> values: {}", epsType,
                        line.substring(0, Math.min(50, line.length())), epsValues);
                return epsValues;
            }
        }
        return new ArrayList<>();
    }

    /**
     * Legacy method for backward compatibility.
     */
    private List<Double> findMetricValues(String[] lines, String... patterns) {
        return findMetricValuesStrict(lines, patterns, new String[]{});
    }

    /**
     * Extract numeric values from a line.
     * Handles Indian number format (1,23,456.78), negative numbers in parentheses, etc.
     */
    private List<Double> extractNumbersFromLine(String line) {
        List<Double> numbers = new ArrayList<>();

        // Normalize whitespace
        line = line.replaceAll("\\s+", " ");

        // Pattern to match numbers:
        // - Optional parentheses for negative: (123.45)
        // - Commas in Indian format: 1,23,456.78 or 12,34,567
        // - Decimal numbers: 123.45
        // - Plain integers: 12345
        Pattern pattern = Pattern.compile(
            "\\(([\\d,]+\\.?\\d*)\\)|" +           // Negative in parens: (123.45)
            "(?<!\\d)([\\d]{1,3}(?:,[\\d]{2,3})*\\.\\d+)|" +  // Indian decimal: 1,23,456.78
            "(?<!\\d)([\\d]+\\.\\d+)|" +           // Simple decimal: 123.45
            "(?<![\\d.])([\\d,]{3,})(?![\\d.])"    // Integer with 3+ chars, not part of decimal
        );

        Matcher matcher = pattern.matcher(line);

        while (matcher.find()) {
            String numStr = null;
            boolean isNegative = false;

            if (matcher.group(1) != null) {
                // Negative number in parentheses
                numStr = matcher.group(1);
                isNegative = true;
            } else if (matcher.group(2) != null) {
                // Indian format decimal
                numStr = matcher.group(2);
            } else if (matcher.group(3) != null) {
                // Simple decimal number
                numStr = matcher.group(3);
            } else if (matcher.group(4) != null) {
                // Integer
                numStr = matcher.group(4);
            }

            if (numStr != null) {
                // Skip if it looks like a year (2020-2030)
                String noComma = numStr.replace(",", "");
                if (noComma.matches("20[2-3]\\d") && !numStr.contains(".")) {
                    continue;
                }
                // Skip serial numbers like "31.12.2025" dates
                if (numStr.matches("\\d{1,2}\\.\\d{1,2}")) {
                    continue;
                }
                // Skip very small numbers that might be row numbers (single digit)
                if (noComma.length() == 1) {
                    continue;
                }

                try {
                    // Remove commas
                    numStr = numStr.replace(",", "");
                    double value = Double.parseDouble(numStr);
                    if (isNegative) value = -value;

                    // Skip zero values
                    if (value != 0) {
                        numbers.add(value);
                    }
                } catch (NumberFormatException e) {
                    // Skip
                }
            }
        }

        return numbers;
    }

    /**
     * Extract quarter information from PDF text.
     * Looks for patterns like "December 31, 2025" or "31.12.2025"
     */
    public String[] extractQuarterInfo(String text) {
        if (text == null) return null;

        // Pattern 1: DD.MM.YYYY or DD/MM/YYYY
        Pattern datePattern = Pattern.compile("(\\d{1,2})[./](\\d{1,2})[./](\\d{4})");
        Matcher dateMatcher = datePattern.matcher(text);

        if (dateMatcher.find()) {
            int day = Integer.parseInt(dateMatcher.group(1));
            int month = Integer.parseInt(dateMatcher.group(2));
            int year = Integer.parseInt(dateMatcher.group(3));

            String quarter = monthToQuarter(month);
            int fiscalYear = monthToFiscalYear(month, year);

            log.info("Extracted quarter from date {}/{}/{}: {} FY{}", day, month, year, quarter, fiscalYear);
            return new String[]{quarter, String.valueOf(fiscalYear)};
        }

        // Pattern 2: Month name + Year
        Pattern monthPattern = Pattern.compile(
            "(January|February|March|April|May|June|July|August|September|October|November|December)\\s*,?\\s*(\\d{4})",
            Pattern.CASE_INSENSITIVE);
        Matcher monthMatcher = monthPattern.matcher(text);

        if (monthMatcher.find()) {
            String monthName = monthMatcher.group(1).toLowerCase();
            int year = Integer.parseInt(monthMatcher.group(2));
            int month = monthNameToNumber(monthName);

            String quarter = monthToQuarter(month);
            int fiscalYear = monthToFiscalYear(month, year);

            log.info("Extracted quarter from {} {}: {} FY{}", monthName, year, quarter, fiscalYear);
            return new String[]{quarter, String.valueOf(fiscalYear)};
        }

        // Pattern 3: Q3 FY26 or Q3FY2026
        Pattern qPattern = Pattern.compile("Q([1-4])\\s*FY\\s*(\\d{2,4})", Pattern.CASE_INSENSITIVE);
        Matcher qMatcher = qPattern.matcher(text);

        if (qMatcher.find()) {
            String quarter = "Q" + qMatcher.group(1);
            String yearStr = qMatcher.group(2);
            if (yearStr.length() == 2) {
                yearStr = "20" + yearStr;
            }
            return new String[]{quarter, yearStr};
        }

        log.warn("Could not extract quarter info, using default Q3 2026");
        return new String[]{"Q3", "2026"};
    }

    private int monthNameToNumber(String monthName) {
        return switch (monthName.substring(0, 3).toLowerCase()) {
            case "jan" -> 1;
            case "feb" -> 2;
            case "mar" -> 3;
            case "apr" -> 4;
            case "may" -> 5;
            case "jun" -> 6;
            case "jul" -> 7;
            case "aug" -> 8;
            case "sep" -> 9;
            case "oct" -> 10;
            case "nov" -> 11;
            case "dec" -> 12;
            default -> 12;
        };
    }

    private String monthToQuarter(int month) {
        if (month >= 1 && month <= 3) return "Q4";   // Jan-Mar = Q4
        if (month >= 4 && month <= 6) return "Q1";   // Apr-Jun = Q1
        if (month >= 7 && month <= 9) return "Q2";   // Jul-Sep = Q2
        return "Q3";                                  // Oct-Dec = Q3
    }

    private int monthToFiscalYear(int month, int calendarYear) {
        // Indian fiscal year: Apr-Mar
        // Jan-Mar 2026 = Q4 FY2026
        // Apr-Dec 2025 = Q1/Q2/Q3 FY2026
        if (month >= 1 && month <= 3) {
            return calendarYear;  // Jan-Mar: same calendar year is FY
        } else {
            return calendarYear + 1;  // Apr-Dec: next year is FY
        }
    }

    /**
     * Detect if PDF is for a bank/NBFC company.
     */
    public boolean isBankPdf(String text) {
        if (text == null) return false;

        String lowerText = text.toLowerCase();
        int bankIndicators = 0;

        if (lowerText.contains("net interest income")) bankIndicators++;
        if (lowerText.contains("interest earned")) bankIndicators++;
        if (lowerText.contains("interest expended")) bankIndicators++;
        if (lowerText.contains("gross npa")) bankIndicators++;
        if (lowerText.contains("net npa")) bankIndicators++;
        if (lowerText.contains("capital adequacy")) bankIndicators++;
        if (lowerText.contains("advances")) bankIndicators++;
        if (lowerText.contains("deposits")) bankIndicators++;

        return bankIndicators >= 2;
    }

    /**
     * Parse a PDF file and return structured results.
     * This is a convenience method for testing.
     */
    public Map<String, Object> parseLocalPdf(String filePath) {
        Map<String, Object> result = new LinkedHashMap<>();

        String text = extractTextFromFile(filePath);
        if (text == null) {
            result.put("error", "Could not read PDF");
            return result;
        }

        // Check if PDF is likely scanned (very little text for multi-page document)
        boolean isLikelyScanned = text.length() < 5000;
        result.put("isLikelyScanned", isLikelyScanned);

        if (isLikelyScanned) {
            log.warn("PDF appears to be scanned (only {} chars extracted). OCR required or use Screener fallback.", text.length());
            result.put("warning", "PDF appears to be scanned. Consider using Screener.in for data.");
        }

        String consolidatedSection = extractConsolidatedSection(text);
        Map<String, List<Double>> metrics = parseMetrics(consolidatedSection);
        String[] quarterInfo = extractQuarterInfo(text);
        boolean isBank = isBankPdf(text);

        result.put("quarter", quarterInfo != null ? quarterInfo[0] : "Q3");
        result.put("fiscalYear", quarterInfo != null ? quarterInfo[1] : "2026");
        result.put("isBank", isBank);
        result.put("metrics", metrics);
        result.put("textLength", text.length());
        result.put("consolidatedSectionLength", consolidatedSection != null ? consolidatedSection.length() : 0);

        return result;
    }

    /**
     * Check if a PDF URL returns a scanned document (needs OCR).
     * Returns true if the PDF likely contains scanned images rather than text.
     */
    public boolean isScannedPdf(String pdfUrl) {
        try {
            String text = downloadAndExtractText(pdfUrl);
            if (text == null) return true;

            // If very little text extracted, it's likely scanned
            return text.length() < 5000;
        } catch (Exception e) {
            log.warn("Error checking if PDF is scanned: {}", e.getMessage());
            return true; // Assume scanned if we can't check
        }
    }
}
