package com.example.announcement_service;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;

/**
 * Advanced PDF diagnostic - checks for images vs text.
 */
public class DiagnosePdfAdvanced {

    public static void main(String[] args) throws Exception {
        String pdfPath = args.length > 0 ? args[0] : "C:/proj/OauthProj/result/anantraj.pdf";

        System.out.println("Advanced PDF Diagnosis: " + pdfPath);
        System.out.println("=".repeat(60));

        PDDocument document = PDDocument.load(new File(pdfPath));
        int totalPages = document.getNumberOfPages();
        System.out.println("Total pages: " + totalPages);

        for (int i = 0; i < totalPages; i++) {
            PDPage page = document.getPage(i);
            PDResources resources = page.getResources();

            int imageCount = 0;
            if (resources != null) {
                for (COSName name : resources.getXObjectNames()) {
                    PDXObject xobject = resources.getXObject(name);
                    if (xobject instanceof PDImageXObject) {
                        imageCount++;
                    }
                }
            }

            // Get text for this page
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(i + 1);
            stripper.setEndPage(i + 1);
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            int textLen = text.trim().length();

            String type;
            if (textLen > 100) {
                type = "TEXT";
            } else if (imageCount > 0) {
                type = "IMAGE (needs OCR)";
            } else {
                type = "EMPTY";
            }

            System.out.printf("Page %2d: %5d chars, %2d images -> %s%n",
                i + 1, textLen, imageCount, type);
        }

        document.close();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("CONCLUSION: Pages marked 'IMAGE (needs OCR)' contain scanned");
        System.out.println("content. These require OCR processing to extract text.");
        System.out.println("\nOptions:");
        System.out.println("1. Use Tesseract OCR integration");
        System.out.println("2. Rely on Screener.in as fallback (recommended)");
        System.out.println("3. Use PDFs that have text layers (not scanned)");
    }
}
