package com.example.announcement_service.client;

import com.example.announcement_service.dto.BseAnnouncementResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

@Component
@Slf4j
public class BseApiClient {

    private final WebClient webClient;
    private static final DateTimeFormatter BSE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public BseApiClient(
            WebClient.Builder builder,
            @Value("${bse.api.base-url:https://api.bseindia.com/BseIndiaAPI/api}") String baseUrl
    ) {
        this.webClient = builder
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Referer", "https://www.bseindia.com/")
                .build();
    }

    /**
     * Fetch corporate announcements from BSE API
     * @param fromDate Start date for announcements
     * @param toDate End date for announcements
     * @param category Category filter (e.g., "Corp. Action", "Result", "AGM/EGM", "Board Meeting")
     * @return Mono of BSE announcement response
     */
    @CircuitBreaker(name = "bseApi", fallbackMethod = "fallbackFetchAnnouncements")
    @Retry(name = "bseApi")
    public Mono<BseAnnouncementResponse> fetchAnnouncements(LocalDate fromDate, LocalDate toDate, String category) {
        String from = fromDate.format(BSE_DATE_FORMAT);
        String to = toDate.format(BSE_DATE_FORMAT);

        log.info("Fetching BSE announcements from {} to {}, category: {}", from, to, category);

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/AnnGetData/w")
                        .queryParam("strCat", category != null ? category : "-1")
                        .queryParam("strPrevDate", from)
                        .queryParam("strScrip", "")
                        .queryParam("strSearch", "P")
                        .queryParam("strToDate", to)
                        .queryParam("strType", "C")
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(BseAnnouncementResponse.class)
                .doOnSuccess(response -> {
                    int count = response.getTable() != null ? response.getTable().size() : 0;
                    log.info("Fetched {} announcements from BSE", count);
                })
                .doOnError(error -> log.error("Error fetching BSE announcements: {}", error.getMessage()));
    }

    /**
     * Fetch announcements for a specific scrip code
     */
    @CircuitBreaker(name = "bseApi", fallbackMethod = "fallbackFetchByScripCode")
    @Retry(name = "bseApi")
    public Mono<BseAnnouncementResponse> fetchAnnouncementsByScripCode(String scripCode, LocalDate fromDate, LocalDate toDate) {
        String from = fromDate.format(BSE_DATE_FORMAT);
        String to = toDate.format(BSE_DATE_FORMAT);

        log.info("Fetching BSE announcements for scrip: {} from {} to {}", scripCode, from, to);

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/AnnGetData/w")
                        .queryParam("strCat", "-1")
                        .queryParam("strPrevDate", from)
                        .queryParam("strScrip", scripCode)
                        .queryParam("strSearch", "P")
                        .queryParam("strToDate", to)
                        .queryParam("strType", "C")
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(BseAnnouncementResponse.class)
                .doOnError(error -> log.error("Error fetching announcements for scrip {}: {}", scripCode, error.getMessage()));
    }

    /**
     * Get the PDF download URL for an announcement
     */
    public String buildPdfUrl(String newsId, String attachmentName) {
        if (attachmentName == null || attachmentName.isBlank()) {
            return null;
        }
        return String.format("https://www.bseindia.com/xml-data/corpfiling/AttachLive/%s", attachmentName);
    }

    // Fallback methods
    private Mono<BseAnnouncementResponse> fallbackFetchAnnouncements(LocalDate fromDate, LocalDate toDate, String category, Throwable ex) {
        log.warn("Fallback triggered for fetchAnnouncements due to: {}", ex.getMessage());
        BseAnnouncementResponse emptyResponse = new BseAnnouncementResponse();
        emptyResponse.setTable(Collections.emptyList());
        return Mono.just(emptyResponse);
    }

    private Mono<BseAnnouncementResponse> fallbackFetchByScripCode(String scripCode, LocalDate fromDate, LocalDate toDate, Throwable ex) {
        log.warn("Fallback triggered for fetchAnnouncementsByScripCode due to: {}", ex.getMessage());
        BseAnnouncementResponse emptyResponse = new BseAnnouncementResponse();
        emptyResponse.setTable(Collections.emptyList());
        return Mono.just(emptyResponse);
    }
}
