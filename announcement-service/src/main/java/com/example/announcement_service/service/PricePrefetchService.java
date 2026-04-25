package com.example.announcement_service.service;

import com.example.announcement_service.model.Announcement;
import com.example.announcement_service.model.AnnouncementPriceCache;
import com.example.announcement_service.repository.AnnouncementPriceCacheRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Service for pre-fetching historical prices when financial result announcements are saved.
 * This runs asynchronously to avoid blocking the announcement sync process.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PricePrefetchService {

    private final AnnouncementPriceCacheRepository priceCacheRepository;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Keywords that indicate a financial results announcement
    private static final List<String> FINANCIAL_RESULT_KEYWORDS = List.of(
            "financial result",
            "quarterly result",
            "un-audited financial",
            "unaudited financial",
            "audited financial"
    );

    /**
     * Pre-fetch price for an announcement if it's a financial result.
     * Runs asynchronously to avoid blocking.
     */
    @Async
    public void prefetchPriceForAnnouncement(Announcement announcement) {
        if (announcement == null || announcement.getNseTicker() == null) {
            return;
        }

        // Check if this is a financial result announcement
        if (!isFinancialResultAnnouncement(announcement)) {
            return;
        }

        String ticker = announcement.getNseTicker().toUpperCase();
        LocalDate announcementDate = announcement.getAnnouncementDate() != null
                ? announcement.getAnnouncementDate().toLocalDate()
                : LocalDate.now();

        // Get the last trading day on or before announcement
        LocalDate targetDate = getLastTradingDay(announcementDate);

        // Check if already cached
        if (priceCacheRepository.existsByTickerAndPriceDate(ticker, targetDate)) {
            log.debug("Price already cached for {} on {}", ticker, targetDate);
            return;
        }

        log.info("Pre-fetching price for {} on {} (announcement: {})",
                ticker, targetDate, announcement.getSubject());

        // Fetch and cache
        try {
            Double price = fetchFromYahooFinance(ticker, targetDate);
            savePriceToCache(ticker, targetDate, price);
            log.info("Pre-fetched price for {} on {}: {}", ticker, targetDate, price);
        } catch (Exception e) {
            log.warn("Failed to pre-fetch price for {} on {}: {}", ticker, targetDate, e.getMessage());
        }
    }

    /**
     * Batch pre-fetch prices for multiple tickers/dates.
     * Useful for catching up on missed announcements.
     */
    @Async
    public void batchPrefetchPrices(List<String> tickers, List<LocalDate> dates) {
        int fetched = 0;
        int skipped = 0;

        for (String ticker : tickers) {
            for (LocalDate date : dates) {
                String normalizedTicker = ticker.toUpperCase();
                LocalDate targetDate = getLastTradingDay(date);

                // Skip if already cached
                if (priceCacheRepository.existsByTickerAndPriceDate(normalizedTicker, targetDate)) {
                    skipped++;
                    continue;
                }

                try {
                    Double price = fetchFromYahooFinance(normalizedTicker, targetDate);
                    savePriceToCache(normalizedTicker, targetDate, price);
                    fetched++;

                    // Rate limiting - avoid hammering Yahoo
                    Thread.sleep(200);
                } catch (Exception e) {
                    log.debug("Failed to fetch {} on {}: {}", normalizedTicker, targetDate, e.getMessage());
                }
            }
        }

        log.info("Batch pre-fetch complete: {} fetched, {} skipped (already cached)", fetched, skipped);
    }

    private boolean isFinancialResultAnnouncement(Announcement announcement) {
        String subject = announcement.getSubject() != null
                ? announcement.getSubject().toLowerCase() : "";
        String category = announcement.getCategory() != null
                ? announcement.getCategory().toLowerCase() : "";

        // Direct category match
        if (category.equals("result")) {
            return true;
        }

        // Keyword match
        for (String keyword : FINANCIAL_RESULT_KEYWORDS) {
            if (subject.contains(keyword) || category.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private LocalDate getLastTradingDay(LocalDate date) {
        java.time.DayOfWeek dow = date.getDayOfWeek();
        if (dow == java.time.DayOfWeek.SUNDAY) {
            return date.minusDays(2);
        } else if (dow == java.time.DayOfWeek.SATURDAY) {
            return date.minusDays(1);
        }
        return date;
    }

    private void savePriceToCache(String ticker, LocalDate date, Double price) {
        try {
            AnnouncementPriceCache cacheEntry = AnnouncementPriceCache.builder()
                    .ticker(ticker)
                    .priceDate(date)
                    .closePrice(price != null ? BigDecimal.valueOf(price) : null)
                    .fetchStatus(price != null ?
                            AnnouncementPriceCache.FetchStatus.SUCCESS :
                            AnnouncementPriceCache.FetchStatus.NOT_FOUND)
                    .build();
            priceCacheRepository.save(cacheEntry);
        } catch (Exception e) {
            log.debug("Could not save to cache: {}", e.getMessage());
        }
    }

    private Double fetchFromYahooFinance(String ticker, LocalDate targetDate) {
        try {
            String yahooSymbol = ticker + ".NS";

            long fromTimestamp = targetDate.atStartOfDay(ZoneId.of("Asia/Kolkata")).toEpochSecond();
            long toTimestamp = targetDate.plusDays(5).atStartOfDay(ZoneId.of("Asia/Kolkata")).toEpochSecond();

            String apiUrl = "https://query1.finance.yahoo.com/v8/finance/chart/" + yahooSymbol
                    + "?period1=" + fromTimestamp
                    + "&period2=" + toTimestamp
                    + "&interval=1d";

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return null;
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            JsonNode root = objectMapper.readTree(response.toString());
            JsonNode resultArray = root.path("chart").path("result");
            if (!resultArray.isArray() || resultArray.size() == 0) {
                return null;
            }

            JsonNode result = resultArray.get(0);
            JsonNode timestamps = result.path("timestamp");
            JsonNode closes = result.path("indicators").path("quote").get(0).path("close");

            if (!timestamps.isArray() || timestamps.size() == 0) {
                return null;
            }

            // Find price on or closest to target date
            Double closestPrice = null;
            long closestDiff = Long.MAX_VALUE;

            for (int i = 0; i < timestamps.size(); i++) {
                if (closes.get(i).isNull()) continue;

                long ts = timestamps.get(i).asLong();
                double closePrice = closes.get(i).asDouble();
                LocalDate date = Instant.ofEpochSecond(ts)
                        .atZone(ZoneId.of("Asia/Kolkata"))
                        .toLocalDate();

                if (date.equals(targetDate)) {
                    return closePrice;
                }

                long diff = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(targetDate, date));
                if (diff < closestDiff) {
                    closestDiff = diff;
                    closestPrice = closePrice;
                }
            }

            return closestPrice;

        } catch (Exception e) {
            log.debug("Error fetching from Yahoo: {}", e.getMessage());
            return null;
        }
    }
}
