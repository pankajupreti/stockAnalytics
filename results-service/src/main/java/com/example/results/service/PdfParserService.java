package com.example.results.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service to download and parse quarterly results from PDFs.
 * Extracts consolidated financial data from BSE/NSE result PDFs.
 * Enhanced to handle Indian financial result formats with tabular data.
 */
@Service
public class PdfParserService {

    private static final Logger log = LoggerFactory.getLogger(PdfParserService.class);

    @Value("${pdf.download.timeout-seconds:30}")
    private int downloadTimeoutSeconds = 30;

    @Value("${pdf.parse.max-pages:10}")
    private int maxPages = 10;

    // Keywords to identify unaudited/consolidated sections
    private static final List<String> RESULTS_SECTION_KEYWORDS = Arrays.asList(
            "UNAUDITED",
            "UN-AUDITED",
            "CONSOLIDATED",
            "FINANCIAL RESULTS",
            "RINANCIAL RESULTS",  // OCR error variant
            "STATEMENT OF",
            "Part II",
            "PART II"
    );

    // Row identifiers for financial metrics - expanded for Indian formats
    private static final Map<String, List<String>> METRIC_PATTERNS = new LinkedHashMap<>();
    static {
        // Revenue patterns - multiple variations
        METRIC_PATTERNS.put("revenue", Arrays.asList(
                "revenue from operations",
                "revenue from operation",
                "total revenue from operations",
                "net sales",
                "income from operations",
                "sales/income from operations",
                "sales / income from operations",
                "i revenue from operations",
                "1 revenue from operations",
                "(i) revenue from operations"
        ));

        METRIC_PATTERNS.put("otherIncome", Arrays.asList(
                "other income",
                "other operating income",
                "ii other income",
                "2 other income",
                "(ii) other income"
        ));

        METRIC_PATTERNS.put("totalIncome", Arrays.asList(
                "total income",
                "total revenue",
                "iii total income",
                "3 total income",
                "(iii) total income",
                "i+ii",
                "(i+ii)"
        ));

        METRIC_PATTERNS.put("totalExpenses", Arrays.asList(
                "total expenses",
                "total expenditure",
                "iv total expenses",
                "4 total expenses",
                "(iv) total expenses"
        ));

        METRIC_PATTERNS.put("pbt", Arrays.asList(
                "profit before tax",
                "profit/(loss) before tax",
                "profit / (loss) before tax",
                "profit before exceptional",
                "pbt",
                "profit before taxation",
                "v profit before tax",
                "5 profit before tax",
                "(v) profit before tax"
        ));

        METRIC_PATTERNS.put("tax", Arrays.asList(
                "tax expense",
                "income tax expense",
                "tax expenses",
                "vi tax expense",
                "6 tax expense"
        ));

        METRIC_PATTERNS.put("pat", Arrays.asList(
                "profit after tax",
                "net profit",
                "profit/(loss) for the period",
                "profit for the period",
                "profit / (loss) for the period",
                "net profit/(loss)",
                "net profit / (loss)",
                "pat",
                "vii profit after tax",
                "7 profit after tax",
                "vii net profit",
                "(vii) profit after tax",
                "profit for the quarter"
        ));

        METRIC_PATTERNS.put("epsBasic", Arrays.asList(
                "basic eps",
                "earnings per share - basic",
                "eps (basic)",
                "basic earnings per share",
                "eps basic",
                "a) basic",
                "(a) basic"
        ));

        METRIC_PATTERNS.put("epsDiluted", Arrays.asList(
                "diluted eps",
                "earnings per share - diluted",
                "eps (diluted)",
                "diluted earnings per share",
                "eps diluted",
                "b) diluted",
                "(b) diluted"
        ));

        // EBITDA patterns
        METRIC_PATTERNS.put("ebitda", Arrays.asList(
                "ebitda",
                "earnings before interest",
                "operating profit"
        ));

        // Bank-specific metrics
        METRIC_PATTERNS.put("nii", Arrays.asList(
                "net interest income",
                "interest earned - interest expended",
                "interest income"
        ));

        METRIC_PATTERNS.put("provisions", Arrays.asList(
                "provisions and contingencies",
                "provisions & contingencies",
                "loan loss provisions",
                "provision for"
        ));
    }

    /**
     * Download PDF from URL and extract text.
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

            try (InputStream inputStream = conn.getInputStream();
                 PDDocument document = PDDocument.load(inputStream)) {

                int numPages = Math.min(document.getNumberOfPages(), maxPages);
                log.info("PDF has {} pages, extracting up to {} pages", document.getNumberOfPages(), numPages);

                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(1);
                stripper.setEndPage(numPages);
                // Sort by position to maintain table structure
                stripper.setSortByPosition(true);

                String text = stripper.getText(document);
                log.info("Extracted {} characters from PDF", text.length());

                // Log first 500 chars for debugging
                if (text.length() > 0) {
                    log.debug("PDF text preview: {}", text.substring(0, Math.min(500, text.length())));
                }

                return text;
            }

        } catch (Exception e) {
            log.error("Error downloading/parsing PDF from {}: {}", pdfUrl, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Extract the financial results section from PDF text.
     */
    public String extractConsolidatedSection(String fullText) {
        if (fullText == null || fullText.isEmpty()) {
            return null;
        }

        String upperText = fullText.toUpperCase();

        // Find start of results section
        int resultsStart = -1;
        for (String keyword : RESULTS_SECTION_KEYWORDS) {
            int idx = upperText.indexOf(keyword.toUpperCase());
            if (idx >= 0) {
                resultsStart = idx;
                log.info("Found results section at position {} with keyword: {}", idx, keyword);
                break;
            }
        }

        if (resultsStart < 0) {
            log.warn("Could not find results section in PDF, using full text");
            return fullText;
        }

        // Extract from results section onwards (up to ~8000 chars to capture more data)
        int endIdx = Math.min(resultsStart + 8000, fullText.length());

        // Try to find end of section
        String[] endMarkers = {"STANDALONE", "NOTES TO", "NOTES:", "FOR AND ON BEHALF", "SIGNATURE"};
        for (String marker : endMarkers) {
            int markerIdx = upperText.indexOf(marker, resultsStart + 200);
            if (markerIdx > resultsStart && markerIdx < endIdx) {
                endIdx = markerIdx;
            }
        }

        String section = fullText.substring(resultsStart, endIdx);
        log.info("Extracted section of {} characters", section.length());
        return section;
    }

    /**
     * Parse financial metrics from text.
     * Enhanced to handle tabular data where numbers follow metric names.
     */
    public Map<String, List<Double>> parseMetrics(String text) {
        Map<String, List<Double>> results = new LinkedHashMap<>();

        if (text == null || text.isEmpty()) {
            return results;
        }

        // Normalize text - fix common OCR issues
        String normalizedText = normalizeText(text);

        String[] lines = normalizedText.split("\\r?\\n");
        log.info("Parsing {} lines of text", lines.length);

        // Debug: Log first few lines to see the format
        for (int d = 0; d < Math.min(20, lines.length); d++) {
            log.debug("Line {}: {}", d, lines[d]);
        }

        // First pass: line-by-line matching
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.length() < 3) continue;

            String lowerLine = line.toLowerCase();

            for (Map.Entry<String, List<String>> entry : METRIC_PATTERNS.entrySet()) {
                String metricKey = entry.getKey();
                if (results.containsKey(metricKey)) continue; // Already found

                for (String pattern : entry.getValue()) {
                    if (lowerLine.contains(pattern.toLowerCase())) {
                        log.debug("Pattern '{}' matched in line: {}", pattern, line);

                        // Try to get numbers from same line
                        List<Double> values = extractNumbers(line);
                        log.debug("Extracted numbers from same line: {}", values);

                        // If no numbers on same line, check next line
                        if (values.isEmpty() && i + 1 < lines.length) {
                            values = extractNumbers(lines[i + 1]);
                            log.debug("Extracted numbers from next line: {}", values);
                        }

                        // If still empty, try combining current and next 2 lines
                        if (values.isEmpty() && i + 2 < lines.length) {
                            values = extractNumbers(line + " " + lines[i + 1] + " " + lines[i + 2]);
                            log.debug("Extracted numbers from combined lines: {}", values);
                        }

                        if (!values.isEmpty()) {
                            results.put(metricKey, values);
                            log.info("Found {}: {} (from line: {})", metricKey, values,
                                    line.substring(0, Math.min(60, line.length())));
                            break;
                        }
                    }
                }
            }
        }

        // Second pass: Look for specific number patterns near keywords using regex
        if (!results.containsKey("revenue")) {
            Double revenue = findMetricValue(normalizedText, "revenue from operations?|total revenue|net sales");
            if (revenue != null) {
                results.put("revenue", List.of(revenue));
                log.info("Found revenue via fallback: {}", revenue);
            }
        }

        if (!results.containsKey("pat")) {
            Double pat = findMetricValue(normalizedText, "profit after tax|net profit|profit for the (period|quarter)");
            if (pat != null) {
                results.put("pat", List.of(pat));
                log.info("Found PAT via fallback: {}", pat);
            }
        }

        if (!results.containsKey("pbt")) {
            Double pbt = findMetricValue(normalizedText, "profit before tax");
            if (pbt != null) {
                results.put("pbt", List.of(pbt));
                log.info("Found PBT via fallback: {}", pbt);
            }
        }

        log.info("Final parsed metrics: {} items - {}", results.size(), results.keySet());
        return results;
    }

    /**
     * Normalize text to fix common OCR issues.
     */
    private String normalizeText(String text) {
        return text
                .replace("RINANCIAL", "FINANCIAL")  // Common OCR error
                .replace("Rinancial", "Financial")
                .replace("rinancial", "financial")
                .replace("T]N", "UN")               // OCR error
                .replace("t]n", "un")
                .replace("( ", "(")
                .replace(" )", ")")
                .replace(" ,", ",");
    }

    /**
     * Find a metric value using regex search.
     */
    private Double findMetricValue(String text, String metricPattern) {
        String lowerText = text.toLowerCase();
        Pattern p = Pattern.compile(metricPattern, Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(lowerText);

        if (m.find()) {
            // Get text after the match
            int start = m.end();
            int end = Math.min(start + 100, lowerText.length());
            String afterMatch = text.substring(start, end);

            List<Double> numbers = extractNumbers(afterMatch);
            if (!numbers.isEmpty()) {
                return numbers.get(0);
            }
        }
        return null;
    }

    /**
     * Check if a line contains a metric pattern.
     */
    private boolean lineContainsMetric(String line, String pattern) {
        String lowerLine = line.toLowerCase();
        String lowerPattern = pattern.toLowerCase();

        if (lowerLine.contains(lowerPattern)) {
            return true;
        }

        // Handle abbreviated patterns - check if all words are present
        String[] words = lowerPattern.split("\\s+");
        if (words.length > 1) {
            boolean allWordsPresent = true;
            for (String word : words) {
                if (word.length() > 2 && !lowerLine.contains(word)) {
                    allWordsPresent = false;
                    break;
                }
            }
            return allWordsPresent;
        }

        return false;
    }

    /**
     * Extract numeric values from a line.
     * Enhanced to handle Indian number formats (1,23,456.78) and various patterns.
     */
    public List<Double> extractNumbers(String line) {
        List<Double> numbers = new ArrayList<>();

        if (line == null || line.isEmpty()) {
            return numbers;
        }

        // Multiple patterns to catch different number formats
        Pattern pattern = Pattern.compile(
                "\\(?\\s*-?\\s*\\d{1,3}(?:,\\d{2,3})*(?:\\.\\d+)?\\s*\\)?|" +  // Indian format with commas
                "\\(?\\s*-?\\s*\\d+\\.\\d+\\s*\\)?|" +                          // Simple decimal
                "(?<=\\s|^)-?\\d{2,}(?=\\s|$)"                                  // Integer with 2+ digits
        );

        Matcher matcher = pattern.matcher(line);

        while (matcher.find()) {
            String numStr = matcher.group().trim();

            // Skip if too short (likely just a single digit like row number)
            if (numStr.replaceAll("[^0-9]", "").length() < 2) {
                continue;
            }

            // Skip if it looks like a date pattern
            if (numStr.matches(".*\\d{1,2}/\\d{1,2}/\\d{2,4}.*")) {
                continue;
            }

            // Skip years 2020-2030 at word boundaries
            if (numStr.matches("20[2-3]\\d") &&
                (line.contains("FY") || line.contains("quarter") || line.contains("Quarter"))) {
                continue;
            }

            try {
                // Handle parentheses as negative
                boolean isNegative = numStr.contains("(") && numStr.contains(")");

                // Clean the string: remove parens, spaces, commas
                numStr = numStr.replaceAll("[()\\s,]", "");

                if (numStr.isEmpty() || numStr.equals("-") || numStr.equals(".")) continue;

                double value = Double.parseDouble(numStr);
                if (isNegative) value = -value;

                // Financial values - skip exactly 0
                if (value != 0) {
                    numbers.add(value);
                }
            } catch (NumberFormatException e) {
                // Skip invalid numbers
            }
        }

        log.debug("extractNumbers from '{}' -> {}",
                line.substring(0, Math.min(60, line.length())), numbers);
        return numbers;
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
        if (lowerText.contains("casa")) bankIndicators++;
        if (lowerText.contains("advances")) bankIndicators++;
        if (lowerText.contains("deposits")) bankIndicators++;

        return bankIndicators >= 2;
    }

    /**
     * Extract quarter information from PDF text.
     * Enhanced with more patterns for Indian quarterly results.
     */
    public String[] extractQuarterInfo(String text) {
        if (text == null) return null;

        // Try multiple patterns
        Pattern[] patterns = {
                // Q3 FY25, Q3FY25
                Pattern.compile("Q([1-4])\\s*FY\\s*(\\d{2,4})", Pattern.CASE_INSENSITIVE),
                // Quarter ended 31st December 2024
                Pattern.compile("quarter\\s+ended\\s+(?:\\d{1,2}(?:st|nd|rd|th)?\\s+)?(january|february|march|april|may|june|july|august|september|october|november|december)\\s*,?\\s*(\\d{4})", Pattern.CASE_INSENSITIVE),
                // Quarter ended December 31, 2024
                Pattern.compile("quarter\\s+ended\\s+(january|february|march|april|may|june|july|august|september|october|november|december)\\s+\\d{1,2},?\\s*(\\d{4})", Pattern.CASE_INSENSITIVE),
                // For the quarter ended 31.12.2024
                Pattern.compile("quarter\\s+ended\\s+(\\d{1,2})[\\./-](\\d{1,2})[\\./-](\\d{4})", Pattern.CASE_INSENSITIVE),
                // 3rd Quarter 2024-25
                Pattern.compile("([1-4])(?:st|nd|rd|th)?\\s+quarter\\s+(\\d{4})", Pattern.CASE_INSENSITIVE),
                // Dec 2024, December 2024
                Pattern.compile("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s*(\\d{4})", Pattern.CASE_INSENSITIVE)
        };

        for (Pattern p : patterns) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                String pattern_str = p.pattern();

                if (pattern_str.contains("Q([1-4])")) {
                    String quarter = "Q" + m.group(1);
                    String year = m.group(2);
                    if (year.length() == 2) {
                        year = "20" + year;
                    }
                    log.info("Extracted quarter info: {} FY{}", quarter, year);
                    return new String[]{quarter, year};
                }
                else if (pattern_str.contains("(\\d{1,2})[\\\\./-](\\d{1,2})")) {
                    // DD.MM.YYYY format
                    int month = Integer.parseInt(m.group(2));
                    String year = m.group(3);
                    String quarter = monthNumToQuarter(month);
                    log.info("Extracted quarter info from date: {} {}", quarter, year);
                    return new String[]{quarter, year};
                }
                else if (pattern_str.contains("january|february|march")) {
                    // Month name format
                    String monthStr = m.group(1).toLowerCase();
                    String year = m.group(2);
                    String quarter = monthToQuarter(monthStr);
                    log.info("Extracted quarter info from month: {} {} -> {}", monthStr, year, quarter);
                    return new String[]{quarter, year};
                }
                else if (pattern_str.contains("([1-4])(?:st|nd|rd|th)?\\\\s+quarter")) {
                    String quarter = "Q" + m.group(1);
                    String year = m.group(2);
                    return new String[]{quarter, year};
                }
                else if (pattern_str.contains("(jan|feb|mar")) {
                    String monthStr = m.group(1).toLowerCase();
                    String year = m.group(2);
                    String quarter = monthToQuarter(monthStr);
                    return new String[]{quarter, year};
                }
            }
        }

        // Default to Q3 2025 if nothing found
        log.warn("Could not extract quarter info from PDF, using default Q3 2025");
        return new String[]{"Q3", "2025"};
    }

    private String monthToQuarter(String month) {
        String m = month.toLowerCase().substring(0, 3);
        return switch (m) {
            case "jan", "feb", "mar" -> "Q4";  // Jan-Mar = Q4 (FY ends March)
            case "apr", "may", "jun" -> "Q1";  // Apr-Jun = Q1
            case "jul", "aug", "sep" -> "Q2";  // Jul-Sep = Q2
            case "oct", "nov", "dec" -> "Q3";  // Oct-Dec = Q3
            default -> "Q3";
        };
    }

    private String monthNumToQuarter(int month) {
        if (month >= 1 && month <= 3) return "Q4";  // Jan-Mar = Q4
        if (month >= 4 && month <= 6) return "Q1";  // Apr-Jun = Q1
        if (month >= 7 && month <= 9) return "Q2";  // Jul-Sep = Q2
        return "Q3";  // Oct-Dec = Q3
    }
}
