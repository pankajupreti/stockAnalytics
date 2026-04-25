package com.example.announcement_service;

import com.example.announcement_service.service.PdfParserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for PDF parsing functionality.
 * Run this test to verify PDF parsing works with local files.
 */
@SpringBootTest
public class PdfParserTest {

    @Autowired
    private PdfParserService pdfParserService;

    @Test
    public void testParseAnantRajPdf() {
        // Path to the test PDF
        String pdfPath = "C:\\proj\\OauthProj\\result\\anantraj.pdf";

        // Parse the PDF
        Map<String, Object> result = pdfParserService.parseLocalPdf(pdfPath);

        System.out.println("=== PDF Parse Results ===");
        System.out.println("Quarter: " + result.get("quarter"));
        System.out.println("Fiscal Year: " + result.get("fiscalYear"));
        System.out.println("Is Bank: " + result.get("isBank"));
        System.out.println("Text Length: " + result.get("textLength"));
        System.out.println("Consolidated Section Length: " + result.get("consolidatedSectionLength"));

        @SuppressWarnings("unchecked")
        Map<String, List<Double>> metrics = (Map<String, List<Double>>) result.get("metrics");

        System.out.println("\n=== Metrics ===");
        if (metrics != null) {
            for (Map.Entry<String, List<Double>> entry : metrics.entrySet()) {
                System.out.println(entry.getKey() + ": " + entry.getValue());
            }
        }

        // Verify expected values from Anant Raj Q3 FY26 consolidated results
        // Expected: Revenue 641.59, PAT 144.23, EPS 4.14
        assertNotNull(metrics, "Metrics should not be null");

        if (metrics.containsKey("revenue")) {
            List<Double> revenue = metrics.get("revenue");
            assertFalse(revenue.isEmpty(), "Revenue should not be empty");
            System.out.println("\nRevenue first value: " + revenue.get(0) + " (expected: 641.59)");
            // Check if close to expected value
            assertTrue(Math.abs(revenue.get(0) - 641.59) < 1.0,
                "Revenue should be close to 641.59, got: " + revenue.get(0));
        }

        if (metrics.containsKey("pat")) {
            List<Double> pat = metrics.get("pat");
            assertFalse(pat.isEmpty(), "PAT should not be empty");
            System.out.println("PAT first value: " + pat.get(0) + " (expected: 144.23)");
            assertTrue(Math.abs(pat.get(0) - 144.23) < 1.0,
                "PAT should be close to 144.23, got: " + pat.get(0));
        }

        if (metrics.containsKey("epsBasic")) {
            List<Double> eps = metrics.get("epsBasic");
            assertFalse(eps.isEmpty(), "EPS should not be empty");
            System.out.println("EPS Basic first value: " + eps.get(0) + " (expected: 4.14)");
            assertTrue(Math.abs(eps.get(0) - 4.14) < 0.1,
                "EPS should be close to 4.14, got: " + eps.get(0));
        }

        // Verify quarter info
        assertEquals("Q3", result.get("quarter"), "Should be Q3");
        assertEquals("2026", result.get("fiscalYear"), "Should be FY2026");

        System.out.println("\n=== Test Passed ===");
    }

    @Test
    public void testExtractTextFromFile() {
        String pdfPath = "C:\\proj\\OauthProj\\result\\anantraj.pdf";

        String text = pdfParserService.extractTextFromFile(pdfPath);
        assertNotNull(text, "Text should not be null");
        assertTrue(text.length() > 1000, "Text should have reasonable length");

        // Print section with consolidated results
        String consolidated = pdfParserService.extractConsolidatedSection(text);
        System.out.println("=== Consolidated Section (first 2000 chars) ===");
        if (consolidated != null) {
            System.out.println(consolidated.substring(0, Math.min(2000, consolidated.length())));
        }
    }
}
