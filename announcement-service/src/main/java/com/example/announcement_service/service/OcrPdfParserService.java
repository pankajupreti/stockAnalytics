package com.example.announcement_service.service;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * OCR-enabled PDF parser for scanned documents.
 * Uses Tesseract OCR to extract text from image-based PDFs.
 *
 * REQUIREMENTS:
 * - Tesseract OCR must be installed on the system
 * - Windows: Download from https://github.com/UB-Mannheim/tesseract/wiki
 * - Set TESSDATA_PREFIX environment variable to tessdata folder
 */
@Service
public class OcrPdfParserService {

    private static final Logger log = LoggerFactory.getLogger(OcrPdfParserService.class);

    @Value("${ocr.tesseract.datapath:C:/Program Files/Tesseract-OCR/tessdata}")
    private String tessdataPath;

    @Value("${ocr.tesseract.language:eng}")
    private String ocrLanguage;

    @Value("${ocr.dpi:300}")
    private int ocrDpi;

    @Value("${pdf.download.timeout-seconds:30}")
    private int downloadTimeoutSeconds;

    private Tesseract tesseract;

    /**
     * Initialize Tesseract OCR engine.
     */
    private Tesseract getTesseract() {
        if (tesseract == null) {
            tesseract = new Tesseract();
            tesseract.setDatapath(tessdataPath);
            tesseract.setLanguage(ocrLanguage);
            tesseract.setPageSegMode(6); // Assume uniform block of text
            tesseract.setOcrEngineMode(1); // Use LSTM OCR Engine
            log.info("Initialized Tesseract OCR with datapath: {}", tessdataPath);
        }
        return tesseract;
    }

    /**
     * Extract text from a PDF file, using OCR for scanned pages.
     *
     * @param filePath Path to the PDF file
     * @return Extracted text from all pages
     */
    public String extractTextWithOcr(String filePath) {
        try {
            log.info("Extracting text with OCR from: {}", filePath);

            PDDocument document = PDDocument.load(new File(filePath));
            StringBuilder fullText = new StringBuilder();

            int totalPages = document.getNumberOfPages();
            PDFTextStripper textStripper = new PDFTextStripper();
            textStripper.setSortByPosition(true);
            PDFRenderer pdfRenderer = new PDFRenderer(document);

            for (int page = 0; page < totalPages; page++) {
                // First try regular text extraction
                textStripper.setStartPage(page + 1);
                textStripper.setEndPage(page + 1);
                String pageText = textStripper.getText(document);

                if (pageText.trim().length() > 50) {
                    // Page has text content
                    fullText.append(pageText);
                    log.debug("Page {}: Extracted {} chars (text)", page + 1, pageText.length());
                } else {
                    // Page is likely scanned - use OCR
                    log.info("Page {}: Using OCR (only {} chars from text extraction)", page + 1, pageText.trim().length());
                    String ocrText = extractTextFromPageWithOcr(pdfRenderer, page);
                    fullText.append(ocrText);
                    log.debug("Page {}: Extracted {} chars (OCR)", page + 1, ocrText.length());
                }
            }

            document.close();

            String result = fullText.toString();
            log.info("Total extracted: {} characters from {} pages", result.length(), totalPages);
            return result;

        } catch (Exception e) {
            log.error("Error extracting text with OCR: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Extract text from a PDF URL, using OCR for scanned pages.
     */
    public String extractTextWithOcrFromUrl(String pdfUrl) {
        Path tempFile = null;
        try {
            log.info("Downloading PDF for OCR from: {}", pdfUrl);

            URL url = new URL(pdfUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(downloadTimeoutSeconds * 1000);
            conn.setReadTimeout(downloadTimeoutSeconds * 1000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

            if (conn.getResponseCode() != 200) {
                log.error("Failed to download PDF: HTTP {}", conn.getResponseCode());
                return null;
            }

            // Save to temp file
            tempFile = Files.createTempFile("pdf_ocr_", ".pdf");
            try (InputStream is = conn.getInputStream()) {
                Files.copy(is, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            // Extract with OCR
            return extractTextWithOcr(tempFile.toString());

        } catch (Exception e) {
            log.error("Error downloading/OCR processing PDF: {}", e.getMessage(), e);
            return null;
        } finally {
            // Cleanup temp file
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception e) {
                    log.warn("Could not delete temp file: {}", tempFile);
                }
            }
        }
    }

    /**
     * Extract text from a single page using OCR.
     */
    private String extractTextFromPageWithOcr(PDFRenderer renderer, int pageIndex) {
        try {
            // Render page to image
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, ocrDpi, ImageType.RGB);

            // Run OCR
            String text = getTesseract().doOCR(image);

            return text != null ? text : "";

        } catch (Exception e) {
            log.warn("OCR failed for page {}: {}", pageIndex + 1, e.getMessage());
            return "";
        }
    }

    /**
     * Check if Tesseract is properly installed and configured.
     */
    public boolean isOcrAvailable() {
        try {
            File tessdata = new File(tessdataPath);
            if (!tessdata.exists() || !tessdata.isDirectory()) {
                log.warn("Tesseract tessdata not found at: {}", tessdataPath);
                return false;
            }

            // Check for language data file
            File engTrainedData = new File(tessdata, ocrLanguage + ".traineddata");
            if (!engTrainedData.exists()) {
                log.warn("Tesseract language data not found: {}", engTrainedData);
                return false;
            }

            log.info("Tesseract OCR is available");
            return true;

        } catch (Exception e) {
            log.warn("Error checking Tesseract availability: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Extract text from a specific page range using OCR.
     *
     * @param filePath PDF file path
     * @param startPage Start page (1-indexed)
     * @param endPage End page (1-indexed, inclusive)
     * @return Extracted text
     */
    public String extractTextFromPages(String filePath, int startPage, int endPage) {
        try {
            log.info("Extracting pages {}-{} with OCR from: {}", startPage, endPage, filePath);

            PDDocument document = PDDocument.load(new File(filePath));
            StringBuilder fullText = new StringBuilder();

            int totalPages = document.getNumberOfPages();
            endPage = Math.min(endPage, totalPages);

            PDFTextStripper textStripper = new PDFTextStripper();
            textStripper.setSortByPosition(true);
            PDFRenderer pdfRenderer = new PDFRenderer(document);

            for (int page = startPage - 1; page < endPage; page++) {
                // First try regular text extraction
                textStripper.setStartPage(page + 1);
                textStripper.setEndPage(page + 1);
                String pageText = textStripper.getText(document);

                if (pageText.trim().length() > 50) {
                    fullText.append(pageText);
                } else {
                    // Use OCR
                    String ocrText = extractTextFromPageWithOcr(pdfRenderer, page);
                    fullText.append(ocrText);
                }
            }

            document.close();
            return fullText.toString();

        } catch (Exception e) {
            log.error("Error extracting pages with OCR: {}", e.getMessage(), e);
            return null;
        }
    }
}
