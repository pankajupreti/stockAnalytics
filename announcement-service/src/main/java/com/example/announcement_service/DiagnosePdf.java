package com.example.announcement_service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;

/**
 * Diagnostic tool to check PDF text extraction per page.
 */
public class DiagnosePdf {

    public static void main(String[] args) throws Exception {
        String pdfPath = args.length > 0 ? args[0] : "C:/proj/OauthProj/result/anantraj.pdf";

        System.out.println("Diagnosing PDF: " + pdfPath);
        System.out.println("=".repeat(60));

        PDDocument document = PDDocument.load(new File(pdfPath));
        int totalPages = document.getNumberOfPages();
        System.out.println("Total pages: " + totalPages);

        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);

        for (int page = 1; page <= totalPages; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);

            String text = stripper.getText(document);
            int charCount = text.length();

            System.out.printf("Page %2d: %5d chars", page, charCount);

            // Show preview of content
            if (charCount > 0) {
                String preview = text.replaceAll("\\s+", " ").trim();
                if (preview.length() > 80) preview = preview.substring(0, 80) + "...";
                System.out.println(" | " + preview);
            } else {
                System.out.println(" | (empty - might be scanned image)");
            }

            // If this looks like the financial results page, print more detail
            if (text.toLowerCase().contains("revenue from operations") ||
                text.toLowerCase().contains("641.59") ||
                text.toLowerCase().contains("profit for the period")) {
                System.out.println("    *** FOUND FINANCIAL DATA ON PAGE " + page + " ***");
                System.out.println("    First 500 chars:");
                System.out.println(text.substring(0, Math.min(500, text.length())));
                System.out.println("    ---");
            }
        }

        document.close();
        System.out.println("=".repeat(60));
    }
}
