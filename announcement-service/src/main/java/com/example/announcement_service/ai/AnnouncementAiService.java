package com.example.announcement_service.ai;

import com.example.announcement_service.dto.AnnouncementDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI service for generating summaries of corporate announcements.
 * Uses OpenAI API (compatible with GPT-4, Claude via proxy, etc.)
 */
@Service
@Slf4j
public class AnnouncementAiService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ai.openai.api-base:https://api.openai.com/v1}")
    private String apiBase;

    @Value("${ai.openai.api-key:}")
    private String apiKey;

    @Value("${ai.openai.model:gpt-4o-mini}")
    private String model;

    @Value("${ai.enabled:false}")
    private boolean aiEnabled;

    public AnnouncementAiService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    /**
     * Generate a concise summary of a single announcement.
     */
    public String summarizeAnnouncement(AnnouncementDTO announcement) {
        if (!isAvailable()) {
            return null;
        }

        try {
            String prompt = buildSingleAnnouncementPrompt(announcement);
            return callOpenAi(prompt);
        } catch (Exception e) {
            log.error("Failed to summarize announcement: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Generate a summary of multiple announcements for a ticker.
     * Groups by category and highlights key information.
     */
    public String summarizeAnnouncements(List<AnnouncementDTO> announcements, String ticker) {
        if (!isAvailable() || announcements == null || announcements.isEmpty()) {
            return null;
        }

        try {
            String prompt = buildMultiAnnouncementPrompt(announcements, ticker);
            return callOpenAi(prompt);
        } catch (Exception e) {
            log.error("Failed to summarize announcements for {}: {}", ticker, e.getMessage());
            return null;
        }
    }

    /**
     * Rank announcements by relevance for investors.
     * Returns a map of newsId -> relevance score (1-10).
     */
    public Map<String, Integer> rankByRelevance(List<AnnouncementDTO> announcements) {
        if (!isAvailable() || announcements == null || announcements.isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            String prompt = buildRankingPrompt(announcements);
            String response = callOpenAi(prompt);
            return parseRankingResponse(response, announcements);
        } catch (Exception e) {
            log.error("Failed to rank announcements: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    public boolean isAvailable() {
        return aiEnabled && apiKey != null && !apiKey.isBlank();
    }

    private String callOpenAi(String userPrompt) {
        String systemPrompt = """
            You are a financial analyst assistant specializing in Indian stock market corporate announcements.
            Provide concise, accurate summaries focusing on information relevant to investors.
            Use professional language and highlight key numbers, dates, and implications.
            Keep responses brief and actionable.
            """;

        Map<String, Object> payload = Map.of(
                "model", model,
                "temperature", 0.3,
                "max_tokens", 500,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        try {
            Map<?, ?> response = webClient.post()
                    .uri(apiBase + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return extractContent(response);
        } catch (Exception e) {
            log.error("OpenAI API call failed: {}", e.getMessage());
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<?, ?> response) {
        if (response == null) return null;
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) return null;
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return message != null ? String.valueOf(message.get("content")).trim() : null;
    }

    private String buildSingleAnnouncementPrompt(AnnouncementDTO ann) {
        return String.format("""
            Summarize this corporate announcement in 2-3 sentences for an investor:

            Company: %s
            Category: %s
            Subject: %s
            Date: %s

            Focus on: What happened? Why does it matter to investors? Any key numbers or dates?
            """,
                ann.getCompanyName(),
                ann.getCategory(),
                ann.getSubject(),
                ann.getBroadcastDateTime() != null ? ann.getBroadcastDateTime() : ann.getAnnouncementDate()
        );
    }

    private String buildMultiAnnouncementPrompt(List<AnnouncementDTO> announcements, String ticker) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Summarize these %d corporate announcements for %s:\n\n",
                announcements.size(), ticker));

        // Group by category
        Map<String, List<AnnouncementDTO>> byCategory = announcements.stream()
                .collect(Collectors.groupingBy(a -> a.getCategory() != null ? a.getCategory() : "General"));

        for (Map.Entry<String, List<AnnouncementDTO>> entry : byCategory.entrySet()) {
            sb.append(String.format("## %s (%d announcements)\n", entry.getKey(), entry.getValue().size()));
            for (AnnouncementDTO ann : entry.getValue().stream().limit(5).toList()) {
                sb.append(String.format("- %s (Date: %s)\n",
                        ann.getSubject(),
                        ann.getBroadcastDateTime() != null ? ann.getBroadcastDateTime() : ann.getAnnouncementDate()));
            }
            sb.append("\n");
        }

        sb.append("""

            Provide a brief summary (3-5 sentences) covering:
            1. Most significant announcements and their implications
            2. Any patterns or themes in recent filings
            3. Key takeaways for investors
            """);

        return sb.toString();
    }

    private String buildRankingPrompt(List<AnnouncementDTO> announcements) {
        StringBuilder sb = new StringBuilder();
        sb.append("Rank these corporate announcements by relevance to investors (1=low, 10=high):\n\n");

        for (int i = 0; i < Math.min(announcements.size(), 20); i++) {
            AnnouncementDTO ann = announcements.get(i);
            sb.append(String.format("%d. [%s] %s - %s\n",
                    i + 1,
                    ann.getNewsId(),
                    ann.getCategory(),
                    ann.getSubject().length() > 100 ? ann.getSubject().substring(0, 100) + "..." : ann.getSubject()));
        }

        sb.append("""

            Return a JSON object with newsId as key and score (1-10) as value.
            Higher scores for: earnings, dividends, acquisitions, board changes, fundraising.
            Lower scores for: routine compliance filings, minor administrative updates.

            Format: {"newsId1": 8, "newsId2": 3, ...}
            """);

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> parseRankingResponse(String response, List<AnnouncementDTO> announcements) {
        try {
            // Extract JSON from response (may have surrounding text)
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String json = response.substring(start, end + 1);
                Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

                Map<String, Integer> result = new HashMap<>();
                for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                    if (entry.getValue() instanceof Number) {
                        result.put(entry.getKey(), ((Number) entry.getValue()).intValue());
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("Failed to parse ranking response: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }
}
