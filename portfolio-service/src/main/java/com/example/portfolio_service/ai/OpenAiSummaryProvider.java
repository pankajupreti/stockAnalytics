package com.example.portfolio_service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.core.lang.Nullable;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.util.*;

// src/main/java/.../ai/OpenAiSummaryProvider.java
@Component
@ConditionalOnProperty(name="ai.openai.api-key")
@RequiredArgsConstructor
// OpenAiSummaryProvider.java (refactored core)

public class OpenAiSummaryProvider implements SummaryProvider {

    private final WebClient.Builder webClientBuilder;

    @Value("${ai.openai.api-base:https://api.openai.com/v1}") String apiBase;
    @Value("${ai.openai.api-key}")  String apiKey;
    @Value("${ai.openai.model:gpt-4o-mini}") String model;
    @Value("${ai.openai.organization:}") String organization;   // optional
    @Value("${ai.openai.project:}")      String project;        // optional
    @Value("${ai.openai.insecure-ssl:false}") boolean insecure; // dev only

    @Override
    public String generate(SummaryService.Facts f) {
        try {
            return callOpenAiOnce(f);                      // first attempt
        } catch (WebClientResponseException.TooManyRequests e) {
            logRateHeaders(e);
            sleepPolitely(retryAfterMillis(e).orElse(900L)); // short, respectful backoff
            try { return callOpenAiOnce(f); }              // single retry
            catch (Exception ignored) { return null; }     // fallback to rule-based
        } catch (Exception e) {
            return null;                                   // fallback to rule-based
        }
    }

    /* -------------------- internals -------------------- */

    /** One API call; throws on error. */
    private String callOpenAiOnce(SummaryService.Facts f) throws Exception {
        WebClient client = buildClient();
        Map<String, Object> body = buildPayload(f);
        //Map<String, Object> extra = buildExtra(niftyPct, "NIFTY50", health, sectorsMap, headlinesMap);

        Map<?, ?> resp = client.post()
                .uri(apiBase + "/chat/completions")
                .headers(h -> {
                    h.setBearerAuth(apiKey);
                    h.setContentType(MediaType.APPLICATION_JSON);
                    if (!organization.isBlank()) h.add("OpenAI-Organization", organization);
                    if (!project.isBlank())      h.add("OpenAI-Project", project);
                })
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        return extractText(resp);
    }

    /** WebClient with optional dev-only insecure SSL. */
    private WebClient buildClient() throws Exception {
        if (!insecure) return webClientBuilder.build();
        // DEV ONLY: bypass certificate checks (never use in prod)
        SslContext ssl = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        HttpClient http = HttpClient.create().secure(t -> t.sslContext(ssl));
        return webClientBuilder.clientConnector(new ReactorClientHttpConnector(http)).build();
    }

    // keep your original entry point
    private Map<String, Object> buildPayload(SummaryService.Facts f) throws Exception {
        return buildPayload(f, null); // no enrichment yet
    }

    /**
     * Refined payload builder. Accepts base facts and (optionally) enriched context:
     * - benchmarkPct (Double), benchmarkName (String)
     * - health: { hhi (Double), concentrationLabel (String), topHoldingPct (Double), topSectorPct (Double) }
     * - sectors: Map<String,String> (ticker -> sector)
     * - headlines: Map<String,List<String>> (ticker -> top titles)
     */
    @SuppressWarnings("unchecked")
    private static final ObjectMapper OM = new ObjectMapper().findAndRegisterModules();

    private Map<String, Object> buildPayload(SummaryService.Facts f, @Nullable Map<String, Object> extra) throws Exception {
        String system = """
      You are a portfolio assistant. Write 2–3 concise sentences:
      1) Summarize today's overall portfolio move (use provided values only).
      2) Name the top drivers (leaders/laggards) with their given day %.
      3) If benchmark/health/news are provided, include one brief insight.
      4) generate a paragraph state some news why a particular stock rose or fell
      Rules:
      - Use ₹ for amounts and include sign.
      - Do NOT invent numbers; never estimate missing data.
      - Prefer neutral-professional tone; keep it under ~1000 words.
      """;

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("portfolioDayPercent", f.dayPercent());
        facts.put("portfolioDayValue",   f.dayValue());
        facts.put("leaders",             f.leaders());   // [{ticker, dayPercent, contributionValue}]
        facts.put("laggards",            f.laggards());

        if (extra != null) {
            Object benchPct  = extra.get("benchmarkPct");
            Object benchName = extra.get("benchmarkName");
            Object health    = extra.get("health");       // map: {hhi, concentrationLabel, topHoldingPct, topSectorPct}
            Object sectors   = extra.get("sectors");      // map: ticker -> sector
            Object headlines = extra.get("headlines");    // map: ticker -> [titles]

            if (benchPct  != null) facts.put("benchmarkDayPercent", benchPct);
            if (benchName != null) facts.put("benchmarkName", benchName);
            if (health    != null) facts.put("health", health);
            if (sectors   != null) facts.put("sectors", sectors);
            if (headlines != null) facts.put("headlines", headlines);
        }

        String factsJson = OM.writeValueAsString(facts);

        // NOTE: no `.formatted(...)`; we just concatenate to avoid % parsing
        String user = """
      Create a short daily brief from the following JSON facts.
      - Use only the numbers present in the JSON.
      - If benchmark/health/news are missing, don't mention them.
      - Mention up to two leaders and one laggard by ticker and day %, if present.
      - Keep to 2–3 sentences, neutral tone, under ~1000 words.
      - generate a paragraph state some news why a particular stock rose or fell

      FACTS:
      """
                + factsJson;

        return Map.of(
                "model", model,
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user",   "content", user)
                )
        );
    }


    @Nullable
    private Map<String, Object> buildExtra(
            @Nullable Double benchmarkPct,
            @Nullable String benchmarkName,
            @Nullable Map<String, Object> health,                  // e.g., Map.of("hhi",..., "concentrationLabel",..., "topHoldingPct",..., "topSectorPct",...)
            @Nullable Map<String, String> sectors,                 // ticker -> sector
            @Nullable Map<String, List<String>> headlines) {       // ticker -> titles
        Map<String, Object> m = new LinkedHashMap<>();
        if (benchmarkPct != null)  m.put("benchmarkPct", benchmarkPct);
        if (benchmarkName != null && !benchmarkName.isBlank()) m.put("benchmarkName", benchmarkName);
        if (health != null && !health.isEmpty())   m.put("health", health);
        if (sectors != null && !sectors.isEmpty()) m.put("sectors", sectors);
        if (headlines != null && !headlines.isEmpty()) m.put("headlines", headlines);
        return m.isEmpty() ? null : m;
    }



    /** Extract the completion text from OpenAI-style response. */
    @SuppressWarnings("unchecked")
    private String extractText(Map<?, ?> resp) {
        if (resp == null) return null;
        var choices = (List<Map<String, Object>>) resp.get("choices");
        if (choices == null || choices.isEmpty()) return null;
        var msg = (Map<String, Object>) choices.get(0).get("message");
        return msg == null ? null : String.valueOf(msg.get("content")).trim();
    }

    /** Parse Retry-After (seconds) or OpenAI reset header into millis. */
    private Optional<Long> retryAfterMillis(WebClientResponseException e) {
        HttpHeaders h = e.getHeaders();
        try {
            String ra = h.getFirst("Retry-After");
            if (ra != null) return Optional.of(Long.parseLong(ra.trim()) * 1000L);
        } catch (Exception ignored) { }
        try {
            String reset = h.getFirst("x-ratelimit-reset-requests"); // epoch seconds
            if (reset != null) {
                long ms = Long.parseLong(reset) * 1000L - System.currentTimeMillis();
                return Optional.of(Math.max(ms, 0));
            }
        } catch (Exception ignored) { }
        return Optional.empty();
    }

    private void sleepPolitely(long waitMs) {
        try { Thread.sleep(Math.min(waitMs, 2000)); } catch (InterruptedException ignored) {}
    }



/*    private void loadExtraFacts(){
        Map<String,Object> extra = new HashMap<>();
        extra.put("benchmarkName", "NIFTY50");
        extra.put("benchmarkPct", niftyDayPct);
        extra.put("health", Map.of(
                "hhi", hhi,
                "concentrationLabel", concentrationLabel,   // e.g., "moderately concentrated"
                "topHoldingPct", topHoldingPct,
                "topSectorPct", topSectorPct
        ));
        extra.put("sectors", sectorsMap);       // ticker -> sector
        extra.put("headlines", headlinesMap);   // ticker -> List<String> (top 1–2 titles)

        Map<String,Object> payload = buildPayload(facts, extra);
    }*/

    private void logRateHeaders(WebClientResponseException e) {
        var h = e.getHeaders();
        System.err.println("OpenAI 429: "
                + " req=" + h.getFirst("x-request-id")
                + " rem-req=" + h.getFirst("x-ratelimit-remaining-requests")
                + " reset-req=" + h.getFirst("x-ratelimit-reset-requests")
                + " rem-tok=" + h.getFirst("x-ratelimit-remaining-tokens")
                + " reset-tok=" + h.getFirst("x-ratelimit-reset-tokens"));
    }

    @Override public boolean isAi() { return true; }
}


