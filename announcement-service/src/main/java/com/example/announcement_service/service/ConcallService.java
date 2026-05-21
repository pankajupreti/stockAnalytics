package com.example.announcement_service.service;

import com.example.announcement_service.model.Announcement;
import com.example.announcement_service.model.ConcallSummary;
import com.example.announcement_service.repository.AnnouncementRepository;
import com.example.announcement_service.repository.ConcallSummaryRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ConcallService {

    private final AnnouncementRepository announcementRepository;
    private final ConcallSummaryRepository concallSummaryRepository;
    private final WebClient webClient;

    @Value("${ai.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${ai.gemini.model:gemini-2.0-flash}")
    private String geminiModel;

    private static final String GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta";

    private static final int CONCALL_MAX_PAGES = 30;
    private static final int MAX_TEXT_LENGTH = 60000;

    private static final String CONCALL_SYSTEM_PROMPT = """
            You are a financial analyst summarizing an Indian company's earnings call transcript.
            Provide a structured, concise summary for investors. Use professional language.
            Focus on forward-looking statements, management guidance, and key financial metrics.
            Keep the total response under 500 words.
            """;

    private static final String CONCALL_USER_PROMPT_TEMPLATE = """
            Summarize this earnings call transcript for %s (%s):

            %s

            Provide the summary in this structure:
            ## Key Highlights
            - (3-5 bullet points of the most important takeaways)

            ## Management Guidance
            - (Revenue/profit guidance, capex plans, growth targets)

            ## Key Risks
            - (Risks or concerns mentioned)

            ## Notable Q&A Points
            - (Important questions from analysts and management responses)
            """;

    public ConcallService(AnnouncementRepository announcementRepository,
                          ConcallSummaryRepository concallSummaryRepository,
                          WebClient.Builder webClientBuilder) {
        this.announcementRepository = announcementRepository;
        this.concallSummaryRepository = concallSummaryRepository;
        this.webClient = webClientBuilder
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .build();
    }

    private boolean isGeminiAvailable() {
        return geminiApiKey != null && !geminiApiKey.isBlank();
    }

    /**
     * Batch query: find "Earnings Call Transcript" announcements for tickers in last N days.
     * Returns map of ticker -> { hasConcall, summaryAvailable, announcementId }
     */
    public Map<String, Map<String, Object>> getConcallStatus(List<String> tickers, int days) {
        if (tickers == null || tickers.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> upperTickers = tickers.stream()
                .map(t -> t.toUpperCase().replace("NSE:", "").replace("BSE:", ""))
                .distinct()
                .collect(Collectors.toList());

        LocalDateTime afterDate = LocalDateTime.now().minusDays(days);

        // Single batch query for all tickers
        List<Announcement> allAnnouncements = announcementRepository
                .findByNseTickersInAndAfterDate(upperTickers, afterDate);

        // Filter for earnings call transcripts
        List<Announcement> concallAnnouncements = allAnnouncements.stream()
                .filter(this::isEarningsCallTranscript)
                .collect(Collectors.toList());

        // Group by ticker, keep only the latest per ticker
        Map<String, Announcement> latestByTicker = new LinkedHashMap<>();
        for (Announcement ann : concallAnnouncements) {
            String ticker = ann.getNseTicker().toUpperCase();
            Announcement existing = latestByTicker.get(ticker);
            if (existing == null || ann.getAnnouncementDate().isAfter(existing.getAnnouncementDate())) {
                latestByTicker.put(ticker, ann);
            }
        }

        // Check which announcements already have cached summaries
        List<Long> annIds = latestByTicker.values().stream()
                .map(Announcement::getId)
                .collect(Collectors.toList());

        Set<Long> summarizedIds = annIds.isEmpty() ? Collections.emptySet()
                : new HashSet<>(concallSummaryRepository.findExistingAnnouncementIds(annIds));

        // Build result map
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String ticker : upperTickers) {
            Announcement ann = latestByTicker.get(ticker);
            if (ann != null) {
                boolean hasSummary = summarizedIds.contains(ann.getId());
                result.put(ticker, Map.of(
                        "hasConcall", true,
                        "summaryAvailable", hasSummary,
                        "announcementId", ann.getId(),
                        "date", ann.getAnnouncementDate().toLocalDate().toString(),
                        "subject", ann.getSubject()
                ));
            } else {
                result.put(ticker, Map.of(
                        "hasConcall", false,
                        "summaryAvailable", false
                ));
            }
        }

        return result;
    }

    /**
     * On-demand: check cache, if miss download PDF, extract text, call Gemini, cache result.
     */
    public ConcallSummary getOrGenerateSummary(Long announcementId) {
        // Check cache first
        Optional<ConcallSummary> cached = concallSummaryRepository.findByAnnouncementId(announcementId);
        if (cached.isPresent()) {
            ConcallSummary existing = cached.get();
            // If previously failed processing, allow retry
            if (existing.getStatus() != ConcallSummary.SummaryStatus.PROCESSING) {
                return existing;
            }
        }

        // Load announcement
        Announcement announcement = announcementRepository.findById(announcementId)
                .orElseThrow(() -> new IllegalArgumentException("Announcement not found: " + announcementId));

        String pdfUrl = announcement.getPdfUrl();
        if (pdfUrl == null || pdfUrl.isBlank()) {
            return saveError(announcementId, announcement, ConcallSummary.SummaryStatus.PDF_ERROR);
        }

        // Mark as processing
        ConcallSummary processing = cached.orElse(ConcallSummary.builder()
                .announcementId(announcementId)
                .ticker(announcement.getNseTicker() != null ? announcement.getNseTicker() : announcement.getTicker())
                .build());
        processing.setStatus(ConcallSummary.SummaryStatus.PROCESSING);
        concallSummaryRepository.save(processing);

        // Download and extract PDF text
        String text;
        int pageCount;
        try {
            PdfExtractionResult extraction = downloadAndExtractConcallText(pdfUrl);
            text = extraction.text;
            pageCount = extraction.pageCount;
        } catch (Exception e) {
            log.error("Failed to download/extract PDF for announcement {}: {}", announcementId, e.getMessage());
            processing.setStatus(ConcallSummary.SummaryStatus.PDF_ERROR);
            processing.setSummaryText("Failed to extract text from PDF: " + e.getMessage());
            return concallSummaryRepository.save(processing);
        }

        if (text == null || text.length() < 500) {
            log.warn("PDF text too short ({} chars) for announcement {}", text != null ? text.length() : 0, announcementId);
            processing.setStatus(ConcallSummary.SummaryStatus.PDF_ERROR);
            processing.setSummaryText("PDF text too short or could not be extracted. The document may be scanned.");
            processing.setTextLength(text != null ? text.length() : 0);
            processing.setPdfPageCount(pageCount);
            return concallSummaryRepository.save(processing);
        }

        // Truncate to max length for AI
        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH);
        }

        // Check if Gemini is available
        if (!isGeminiAvailable()) {
            processing.setStatus(ConcallSummary.SummaryStatus.AI_DISABLED);
            processing.setSummaryText("AI summarization is not configured. Set GEMINI_API_KEY to enable.");
            processing.setTextLength(text.length());
            processing.setPdfPageCount(pageCount);
            return concallSummaryRepository.save(processing);
        }

        // Call Gemini
        try {
            String ticker = announcement.getNseTicker() != null ? announcement.getNseTicker() : announcement.getTicker();
            String companyName = announcement.getCompanyName();
            String userPrompt = String.format(CONCALL_USER_PROMPT_TEMPLATE, companyName, ticker, text);

            String summary = callGemini(CONCALL_SYSTEM_PROMPT, userPrompt);

            processing.setStatus(ConcallSummary.SummaryStatus.SUCCESS);
            processing.setSummaryText(summary);
            processing.setTextLength(text.length());
            processing.setPdfPageCount(pageCount);
            processing.setGeneratedAt(LocalDateTime.now());

            // Try to extract quarter from subject
            String subject = announcement.getSubject();
            if (subject != null) {
                processing.setQuarter(extractQuarterFromSubject(subject));
            }

            return concallSummaryRepository.save(processing);
        } catch (Exception e) {
            log.error("Gemini summarization failed for announcement {}: {}", announcementId, e.getMessage());
            processing.setStatus(ConcallSummary.SummaryStatus.AI_ERROR);
            processing.setSummaryText("AI summarization failed: " + e.getMessage());
            processing.setTextLength(text.length());
            processing.setPdfPageCount(pageCount);
            return concallSummaryRepository.save(processing);
        }
    }

    /**
     * Call Gemini API with system instruction and user prompt.
     * Retries up to 3 times on 429 (rate limit) with exponential backoff.
     */
    private String callGemini(String systemPrompt, String userPrompt) {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return callGeminiOnce(systemPrompt, userPrompt);
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("429") && attempt < maxRetries) {
                    long waitSecs = attempt * 30L; // 30s, 60s, 90s
                    log.warn("Gemini rate limited (attempt {}/{}), waiting {}s before retry...", attempt, maxRetries, waitSecs);
                    try { Thread.sleep(waitSecs * 1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                throw e;
            }
        }
        throw new RuntimeException("Gemini failed after " + maxRetries + " retries");
    }

    @SuppressWarnings("unchecked")
    private String callGeminiOnce(String systemPrompt, String userPrompt) {
        String url = GEMINI_API_BASE + "/models/" + geminiModel + ":generateContent?key=" + geminiApiKey;

        // Gemini request format
        Map<String, Object> payload = Map.of(
                "system_instruction", Map.of(
                        "parts", List.of(Map.of("text", systemPrompt))
                ),
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", userPrompt)))
                ),
                "generationConfig", Map.of(
                        "temperature", 0.3,
                        "maxOutputTokens", 1500
                )
        );

        log.info("Calling Gemini API with model: {}", geminiModel);

        Map<?, ?> response = webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response == null) {
            throw new RuntimeException("Gemini returned null response");
        }

        // Extract text from Gemini response
        // Response format: { candidates: [{ content: { parts: [{ text: "..." }] } }] }
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            Object error = response.get("error");
            if (error != null) {
                throw new RuntimeException("Gemini API error: " + error);
            }
            throw new RuntimeException("Gemini returned no candidates");
        }

        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        if (content == null) {
            throw new RuntimeException("Gemini candidate has no content");
        }

        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        if (parts == null || parts.isEmpty()) {
            throw new RuntimeException("Gemini content has no parts");
        }

        String text = String.valueOf(parts.get(0).get("text")).trim();
        log.info("Gemini response: {} chars", text.length());
        return text;
    }

    private boolean isEarningsCallTranscript(Announcement ann) {
        String subject = ann.getSubject();
        if (subject == null) return false;
        String lower = subject.toLowerCase();

        // Exclude non-transcript announcements (audio recordings, schedule notices, cancellations)
        if (lower.contains("audio recording") || lower.contains("recording of") ||
            lower.contains("intimation") || lower.contains("schedule") ||
            lower.contains("cancellation") || lower.contains("cancelled")) {
            return false;
        }

        return lower.contains("earnings call transcript") ||
               lower.contains("concall transcript") ||
               lower.contains("conference call transcript") ||
               (lower.contains("transcript") && (lower.contains("earning") || lower.contains("quarter") || lower.contains("result"))) ||
               (lower.contains("earnings call") && !lower.contains("audio") && !lower.contains("recording")) ||
               (lower.contains("conference call") && !lower.contains("audio") && !lower.contains("recording"));
    }

    private ConcallSummary saveError(Long announcementId, Announcement ann, ConcallSummary.SummaryStatus status) {
        ConcallSummary summary = ConcallSummary.builder()
                .announcementId(announcementId)
                .ticker(ann.getNseTicker() != null ? ann.getNseTicker() : ann.getTicker())
                .status(status)
                .summaryText("No PDF URL available for this announcement.")
                .build();
        return concallSummaryRepository.save(summary);
    }

    private PdfExtractionResult downloadAndExtractConcallText(String pdfUrl) throws Exception {
        log.info("Downloading concall PDF from: {}", pdfUrl);

        // Download full PDF to byte array first (streaming can cause truncation/EOF errors)
        byte[] pdfBytes = downloadPdfBytes(pdfUrl);
        log.info("Downloaded {} bytes from PDF", pdfBytes.length);

        try (PDDocument document = PDDocument.load(pdfBytes)) {

            int totalPages = document.getNumberOfPages();
            int pagesToExtract = Math.min(totalPages, CONCALL_MAX_PAGES);
            log.info("Concall PDF has {} pages, extracting up to {}", totalPages, pagesToExtract);

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(pagesToExtract);
            stripper.setSortByPosition(true);

            String text = stripper.getText(document);
            log.info("Extracted {} chars from concall PDF", text.length());

            return new PdfExtractionResult(text, totalPages);
        }
    }

    private byte[] downloadPdfBytes(String pdfUrl) throws Exception {
        // Follow redirects (BSE often redirects)
        String currentUrl = pdfUrl;
        for (int redirects = 0; redirects < 5; redirects++) {
            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "application/pdf,*/*");

            int responseCode = conn.getResponseCode();

            if (responseCode == 301 || responseCode == 302 || responseCode == 303 || responseCode == 307) {
                String location = conn.getHeaderField("Location");
                log.info("PDF redirect {} -> {}", responseCode, location);
                if (location == null) throw new RuntimeException("Redirect with no Location header");
                // Handle relative redirects
                if (location.startsWith("/")) {
                    URL base = new URL(currentUrl);
                    currentUrl = base.getProtocol() + "://" + base.getHost() + location;
                } else {
                    currentUrl = location;
                }
                conn.disconnect();
                continue;
            }

            if (responseCode != 200) {
                throw new RuntimeException("HTTP " + responseCode + " downloading PDF from " + currentUrl);
            }

            try (InputStream is = conn.getInputStream();
                 java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                byte[] result = baos.toByteArray();
                if (result.length < 100) {
                    throw new RuntimeException("Downloaded PDF too small (" + result.length + " bytes), likely not a valid PDF");
                }
                return result;
            }
        }
        throw new RuntimeException("Too many redirects downloading PDF");
    }

    private String extractQuarterFromSubject(String subject) {
        if (subject == null) return null;
        String upper = subject.toUpperCase();

        for (String q : new String[]{"Q1", "Q2", "Q3", "Q4"}) {
            if (upper.contains(q)) {
                int qIdx = upper.indexOf(q);
                String after = upper.substring(qIdx);
                if (after.matches("Q[1-4]\\s*FY\\s*\\d{2,4}.*")) {
                    int fyIdx = after.indexOf("FY");
                    String yearPart = after.substring(fyIdx + 2).trim();
                    StringBuilder year = new StringBuilder();
                    for (char c : yearPart.toCharArray()) {
                        if (Character.isDigit(c)) year.append(c);
                        else break;
                    }
                    if (year.length() >= 2) {
                        return q + " FY" + year;
                    }
                }
                return q;
            }
        }
        return null;
    }

    private record PdfExtractionResult(String text, int pageCount) {}
}
