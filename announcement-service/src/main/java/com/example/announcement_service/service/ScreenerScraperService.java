package com.example.announcement_service.service;

import com.example.announcement_service.model.ParsedResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service to scrape quarterly financial results from Screener.in
 * This is a fallback when PDF parsing fails.
 */
@Service
@Slf4j
public class ScreenerScraperService {

    // Use consolidated URL for accurate data (matches company PDF results)
    private static final String SCREENER_URL = "https://www.screener.in/company/";
    private static final String SCREENER_CONSOLIDATED_SUFFIX = "/consolidated/";
    private static final int TIMEOUT_MS = 15000;

    /**
     * Fetch quarterly results for a ticker from Screener.in
     */
    public List<ParsedResult> fetchQuarterlyResults(String ticker) {
        List<ParsedResult> results = new ArrayList<>();

        try {
            // Try consolidated first (matches PDF data better)
            String html = fetchHtml(SCREENER_URL + ticker + SCREENER_CONSOLIDATED_SUFFIX);

            // If consolidated fails, try standalone
            if (html == null || html.isEmpty()) {
                log.info("Consolidated data not available for {}, trying standalone", ticker);
                html = fetchHtml(SCREENER_URL + ticker + "/");
            }

            if (html == null || html.isEmpty()) {
                log.warn("Could not fetch Screener page for {}", ticker);
                return results;
            }

            log.info("Fetched {} chars of HTML for {}", html.length(), ticker);

            // Parse quarterly results table
            results = parseQuarterlyResultsTable(html, ticker);
            log.info("Scraped {} quarterly results for {} from Screener", results.size(), ticker);

        } catch (Exception e) {
            log.error("Error scraping Screener for {}: {}", ticker, e.getMessage(), e);
        }

        return results;
    }

    private String fetchHtml(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
            conn.setInstanceFollowRedirects(true);

            if (conn.getResponseCode() != 200) {
                log.error("Screener returned HTTP {} for {}", conn.getResponseCode(), urlStr);
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            return sb.toString();

        } catch (Exception e) {
            log.error("Failed to fetch {}: {}", urlStr, e.getMessage());
            return null;
        }
    }

    /**
     * Parse the quarterly results table from Screener HTML.
     */
    private List<ParsedResult> parseQuarterlyResultsTable(String html, String ticker) {
        List<ParsedResult> results = new ArrayList<>();

        try {
            // Find the first quarterly results table (data-result-table)
            int tableStart = html.indexOf("data-result-table");
            if (tableStart < 0) {
                log.warn("Could not find quarterly results table for {}", ticker);
                return results;
            }

            // Find the closing </table> tag
            int tableEnd = html.indexOf("</table>", tableStart);
            if (tableEnd < 0) {
                tableEnd = Math.min(tableStart + 15000, html.length());
            }

            String tableHtml = html.substring(tableStart, tableEnd);
            log.debug("Table section length: {} chars", tableHtml.length());

            // Extract quarter headers
            List<String> quarters = extractQuarterHeaders(tableHtml);
            if (quarters.isEmpty()) {
                log.warn("Could not extract quarter headers for {}", ticker);
                return results;
            }

            log.info("Found {} quarters for {}: {}", quarters.size(), ticker, quarters);

            // Extract metrics row by row
            Map<String, List<Double>> metrics = new LinkedHashMap<>();
            metrics.put("Sales", extractRowValues(tableHtml, "Sales"));
            metrics.put("Expenses", extractRowValues(tableHtml, "Expenses"));
            metrics.put("Operating Profit", extractRowValues(tableHtml, "Operating Profit"));
            metrics.put("Other Income", extractRowValues(tableHtml, "Other Income"));
            metrics.put("Profit before tax", extractRowValues(tableHtml, "Profit before tax"));
            metrics.put("Net Profit", extractRowValues(tableHtml, "Net Profit"));
            metrics.put("EPS in Rs", extractRowValues(tableHtml, "EPS in Rs"));

            log.info("Extracted metrics for {}: Sales={}, NetProfit={}, EPS={}",
                    ticker,
                    metrics.get("Sales"),
                    metrics.get("Net Profit"),
                    metrics.get("EPS in Rs"));

            // Build ParsedResult for each quarter (up to 8 most recent)
            int numQuarters = Math.min(8, quarters.size());
            for (int i = 0; i < numQuarters; i++) {
                String quarterStr = quarters.get(i);
                String[] quarterInfo = parseQuarterString(quarterStr);
                if (quarterInfo == null) {
                    log.debug("Could not parse quarter string: {}", quarterStr);
                    continue;
                }

                ParsedResult result = ParsedResult.builder()
                        .ticker(ticker.toUpperCase())
                        .quarter(quarterInfo[0])
                        .fiscalYear(Integer.parseInt(quarterInfo[1]))
                        .companyType(ParsedResult.CompanyType.REGULAR)
                        .parsedAt(LocalDateTime.now())
                        .parseStatus(ParsedResult.ParseStatus.SUCCESS)
                        .remarks("Scraped from Screener.in")
                        .build();

                // Set metrics
                setMetricIfPresent(result, "revenue", metrics.get("Sales"), i);
                setMetricIfPresent(result, "expenses", metrics.get("Expenses"), i);
                setMetricIfPresent(result, "ebitda", metrics.get("Operating Profit"), i);
                setMetricIfPresent(result, "otherIncome", metrics.get("Other Income"), i);
                setMetricIfPresent(result, "pbt", metrics.get("Profit before tax"), i);
                setMetricIfPresent(result, "pat", metrics.get("Net Profit"), i);
                setMetricIfPresent(result, "eps", metrics.get("EPS in Rs"), i);

                // Calculate margins
                result.calculateMargins();

                // Only add if we have at least Sales or Net Profit
                if (result.getRevenue() != null || result.getPat() != null) {
                    results.add(result);
                }
            }

        } catch (Exception e) {
            log.error("Error parsing Screener table for {}: {}", ticker, e.getMessage(), e);
        }

        return results;
    }

    /**
     * Extract quarter headers from <th> tags.
     * Format: <th class="...">Dec 2022</th>
     */
    private List<String> extractQuarterHeaders(String tableHtml) {
        List<String> quarters = new ArrayList<>();

        // Find thead section
        int theadStart = tableHtml.indexOf("<thead>");
        int theadEnd = tableHtml.indexOf("</thead>");
        if (theadStart < 0 || theadEnd < 0) {
            log.warn("Could not find thead section");
            return quarters;
        }

        String thead = tableHtml.substring(theadStart, theadEnd);

        // Extract all <th> content
        Pattern thPattern = Pattern.compile("<th[^>]*>([^<]*(?:<[^>]*>[^<]*)*)</th>", Pattern.DOTALL);
        Matcher thMatcher = thPattern.matcher(thead);

        while (thMatcher.find()) {
            String thContent = thMatcher.group(1).trim();
            // Remove any nested tags and whitespace
            thContent = thContent.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();

            // Check if it matches month year pattern
            if (thContent.matches("(?i)(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+\\d{4}")) {
                quarters.add(thContent);
            }
        }

        return quarters;
    }

    /**
     * Extract values for a specific row (metric).
     * Rows can have the metric name in a button or directly in td.
     */
    private List<Double> extractRowValues(String tableHtml, String metricName) {
        List<Double> values = new ArrayList<>();

        try {
            // Find the row containing this metric
            // Pattern: look for the metric name, then find all td values until </tr>
            String lowerHtml = tableHtml.toLowerCase();
            String lowerMetric = metricName.toLowerCase();

            int metricPos = lowerHtml.indexOf(lowerMetric);
            if (metricPos < 0) {
                log.debug("Metric '{}' not found in table", metricName);
                return values;
            }

            // Find the </tr> after the metric
            int rowEnd = tableHtml.indexOf("</tr>", metricPos);
            if (rowEnd < 0) {
                rowEnd = Math.min(metricPos + 500, tableHtml.length());
            }

            String rowHtml = tableHtml.substring(metricPos, rowEnd);

            // Extract all <td> values (excluding the first one which is the label)
            Pattern tdPattern = Pattern.compile("<td[^>]*>([^<]*)</td>");
            Matcher tdMatcher = tdPattern.matcher(rowHtml);

            boolean firstTd = true;
            while (tdMatcher.find()) {
                if (firstTd) {
                    // Skip first td if it contains the metric name
                    String content = tdMatcher.group(1).trim();
                    if (content.isEmpty() || content.toLowerCase().contains(lowerMetric)) {
                        firstTd = false;
                        continue;
                    }
                }
                firstTd = false;

                String tdValue = tdMatcher.group(1).trim();
                Double parsed = parseNumericValue(tdValue);
                if (parsed != null) {
                    values.add(parsed);
                }
            }

        } catch (Exception e) {
            log.debug("Error extracting row '{}': {}", metricName, e.getMessage());
        }

        return values;
    }

    private void setMetricIfPresent(ParsedResult result, String metricName, List<Double> values, int index) {
        if (values != null && index < values.size() && values.get(index) != null) {
            Double value = values.get(index);
            switch (metricName) {
                case "revenue" -> result.setRevenue(value);
                case "expenses" -> result.setTotalExpenses(value);
                case "ebitda" -> result.setEbitda(value);
                case "otherIncome" -> result.setOtherIncome(value);
                case "pbt" -> result.setPbt(value);
                case "pat" -> result.setPat(value);
                case "eps" -> result.setEpsBasic(value);
            }
        }
    }

    /**
     * Parse a numeric value from text.
     */
    private Double parseNumericValue(String text) {
        if (text == null || text.isEmpty()) return null;

        text = text.replaceAll("&nbsp;", " ").replaceAll("\\s+", "").trim();

        if (text.isEmpty() || text.equals("-") || text.equals("--")) return null;

        // Remove percentage sign and commas
        text = text.replace("%", "").replace(",", "");

        // Handle parentheses as negative
        boolean isNegative = text.startsWith("(") && text.endsWith(")");
        if (isNegative) {
            text = text.substring(1, text.length() - 1);
        }

        try {
            double value = Double.parseDouble(text);
            return isNegative ? -value : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parse "Dec 2024" -> ["Q3", "2025"] (fiscal year)
     * Indian fiscal year: Apr-Mar
     */
    private String[] parseQuarterString(String quarterStr) {
        try {
            String[] parts = quarterStr.trim().split("\\s+");
            if (parts.length != 2) return null;

            String month = parts[0].toLowerCase();
            int calendarYear = Integer.parseInt(parts[1]);

            String quarter;
            int fiscalYear;

            switch (month.substring(0, 3)) {
                case "jan", "feb", "mar" -> {
                    quarter = "Q4";
                    fiscalYear = calendarYear; // Jan-Mar 2024 = Q4 FY2024
                }
                case "apr", "may", "jun" -> {
                    quarter = "Q1";
                    fiscalYear = calendarYear + 1; // Apr-Jun 2024 = Q1 FY2025
                }
                case "jul", "aug", "sep" -> {
                    quarter = "Q2";
                    fiscalYear = calendarYear + 1; // Jul-Sep 2024 = Q2 FY2025
                }
                case "oct", "nov", "dec" -> {
                    quarter = "Q3";
                    fiscalYear = calendarYear + 1; // Oct-Dec 2024 = Q3 FY2025
                }
                default -> {
                    return null;
                }
            }

            return new String[]{quarter, String.valueOf(fiscalYear)};
        } catch (Exception e) {
            log.debug("Error parsing quarter string '{}': {}", quarterStr, e.getMessage());
            return null;
        }
    }
}
