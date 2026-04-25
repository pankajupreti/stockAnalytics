package com.example.announcement_service;

import com.example.announcement_service.service.PdfParserService;

import java.util.List;
import java.util.Map;

/**
 * Standalone test for PDF parsing.
 * Run this main method to test PDF parsing without Spring context.
 *
 * Usage: Run in IDE with path to PDF as argument, or edit the hardcoded path below.
 */
public class TestPdfParser {

    public static void main(String[] args) {
        String pdfPath = args.length > 0 ? args[0] : "C:\\proj\\OauthProj\\result\\anantraj.pdf";

        System.out.println("Testing PDF parsing for: " + pdfPath);
        System.out.println("=".repeat(60));

        PdfParserService parser = new PdfParserService();

        // Extract text
        String text = parser.extractTextFromFile(pdfPath);
        if (text == null) {
            System.out.println("ERROR: Could not read PDF file");
            return;
        }

        System.out.println("Extracted " + text.length() + " characters from PDF");

        // Extract consolidated section
        String consolidated = parser.extractConsolidatedSection(text);
        System.out.println("Consolidated section: " + (consolidated != null ? consolidated.length() : 0) + " characters");

        // Show sample of consolidated section
        System.out.println("\n=== Consolidated Section Sample ===");
        if (consolidated != null) {
            System.out.println(consolidated.substring(0, Math.min(1500, consolidated.length())));
        }
        System.out.println("=".repeat(60));

        // Parse metrics
        Map<String, List<Double>> metrics = parser.parseMetrics(consolidated);

        System.out.println("\n=== Parsed Metrics ===");
        for (Map.Entry<String, List<Double>> entry : metrics.entrySet()) {
            System.out.printf("%-15s: %s%n", entry.getKey(), entry.getValue());
        }

        // Extract quarter info
        String[] quarterInfo = parser.extractQuarterInfo(text);
        System.out.println("\n=== Quarter Info ===");
        System.out.println("Quarter: " + (quarterInfo != null ? quarterInfo[0] : "N/A"));
        System.out.println("Fiscal Year: " + (quarterInfo != null ? quarterInfo[1] : "N/A"));

        // Check if bank
        boolean isBank = parser.isBankPdf(text);
        System.out.println("Is Bank/NBFC: " + isBank);

        // Verify expected values
        System.out.println("\n=== Verification ===");
        verifyMetric(metrics, "revenue", 641.59);
        verifyMetric(metrics, "pat", 144.23);
        verifyMetric(metrics, "epsBasic", 4.14);
        verifyMetric(metrics, "pbt", 171.78);
        verifyMetric(metrics, "totalIncome", 660.38);

        System.out.println("\n=== Test Complete ===");
    }

    private static void verifyMetric(Map<String, List<Double>> metrics, String key, double expected) {
        if (metrics.containsKey(key) && !metrics.get(key).isEmpty()) {
            double actual = metrics.get(key).get(0);
            boolean match = Math.abs(actual - expected) < 1.0;
            System.out.printf("%s: %.2f (expected: %.2f) %s%n",
                key, actual, expected, match ? "OK" : "MISMATCH");
        } else {
            System.out.printf("%s: NOT FOUND (expected: %.2f) FAIL%n", key, expected);
        }
    }
}
