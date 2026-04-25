package com.example.announcement_service.service;

import com.example.announcement_service.client.ResultsServiceClient;
import com.example.announcement_service.model.Announcement;
import com.example.announcement_service.repository.AnnouncementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scheduled job to periodically sync announcements from BSE API.
 * Runs every 5 minutes during market hours on weekdays.
 * Also handles auto-refresh of Screener data for financial results.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnnouncementSyncScheduler {

    private final AnnouncementService announcementService;
    private final AnnouncementRepository announcementRepository;
    private final ResultsServiceClient resultsServiceClient;

    @Value("${announcement.sync.enabled:true}")
    private boolean syncEnabled;

    @Value("${announcement.sync.timezone:Asia/Kolkata}")
    private String timezone;

    /**
     * Sync announcements every 5 minutes, all days
     */
    @Scheduled(cron = "${announcement.sync.cron:0 0/5 * * * *}", zone = "${announcement.sync.timezone:Asia/Kolkata}")
    public void syncDuringMarketHours() {
        if (!syncEnabled) {
            log.debug("Announcement sync is disabled");
            return;
        }

        log.info("Starting scheduled announcement sync");
        try {
            LocalDate today = LocalDate.now(ZoneId.of(timezone));
            int savedCount = announcementService.syncAnnouncements(today, today, null);
            log.info("Scheduled sync completed. Saved {} new announcements", savedCount);
        } catch (Exception e) {
            log.error("Error during scheduled sync: {}", e.getMessage(), e);
        }
    }

    /**
     * Daily sync at 7 PM IST to catch any missed announcements
     */
    @Scheduled(cron = "${announcement.sync.daily-cron:0 0 19 * * MON-FRI}", zone = "${announcement.sync.timezone:Asia/Kolkata}")
    public void dailySync() {
        if (!syncEnabled) {
            log.debug("Announcement sync is disabled");
            return;
        }

        log.info("Starting daily announcement sync");
        try {
            LocalDate today = LocalDate.now(ZoneId.of(timezone));
            // Sync last 2 days to catch any missed announcements
            LocalDate fromDate = today.minusDays(1);
            int savedCount = announcementService.syncAnnouncements(fromDate, today, null);
            log.info("Daily sync completed. Saved {} new announcements", savedCount);
        } catch (Exception e) {
            log.error("Error during daily sync: {}", e.getMessage(), e);
        }
    }

    /**
     * Weekend catch-up sync on Monday morning at 8:30 AM IST
     */
    @Scheduled(cron = "${announcement.sync.weekend-cron:0 30 8 * * MON}", zone = "${announcement.sync.timezone:Asia/Kolkata}")
    public void weekendCatchupSync() {
        if (!syncEnabled) {
            log.debug("Announcement sync is disabled");
            return;
        }

        log.info("Starting weekend catch-up sync");
        try {
            LocalDate today = LocalDate.now(ZoneId.of(timezone));
            // Sync from Friday to today
            LocalDate friday = today.minusDays(3);
            int savedCount = announcementService.syncAnnouncements(friday, today, null);
            log.info("Weekend catch-up sync completed. Saved {} new announcements", savedCount);
        } catch (Exception e) {
            log.error("Error during weekend catch-up sync: {}", e.getMessage(), e);
        }
    }

    /**
     * Update announcements with missing nseTicker values.
     * Runs hourly to populate nseTicker for announcements synced before mappings existed.
     */
    @Scheduled(cron = "0 0 * * * *", zone = "${announcement.sync.timezone:Asia/Kolkata}")
    public void updateMissingMappings() {
        if (!syncEnabled) {
            return;
        }

        log.debug("Checking for announcements with missing nseTicker mappings");
        try {
            int updatedCount = announcementService.updateMissingNseTickers();
            if (updatedCount > 0) {
                log.info("Updated {} announcements with nseTicker mappings", updatedCount);
            }
        } catch (Exception e) {
            log.error("Error updating missing nseTicker mappings: {}", e.getMessage(), e);
        }
    }

    /**
     * Refresh Screener data for stocks that announced results in the last 2 hours.
     * Runs every 30 minutes to ensure Screener data is fresh after BSE announcements.
     * Screener typically updates 30-60 minutes after BSE announcement.
     */
    @Scheduled(cron = "0 30 * * * *", zone = "${announcement.sync.timezone:Asia/Kolkata}")
    public void refreshScreenerForRecentResults() {
        if (!syncEnabled) {
            return;
        }

        log.debug("Checking for recent financial results to refresh Screener data");
        try {
            // Find financial result announcements from last 2 hours
            LocalDateTime cutoff = LocalDateTime.now(ZoneId.of(timezone)).minusHours(2);
            List<Announcement> recentAnnouncements = announcementRepository.findByAnnouncementDateAfter(cutoff);

            // Filter for financial results and get unique tickers
            Set<String> tickers = recentAnnouncements.stream()
                    .filter(this::isFinancialResultAnnouncement)
                    .map(Announcement::getNseTicker)
                    .filter(t -> t != null && !t.isBlank())
                    .collect(Collectors.toSet());

            if (tickers.isEmpty()) {
                log.debug("No recent financial results to refresh");
                return;
            }

            log.info("Refreshing Screener data for {} tickers with recent results: {}", tickers.size(), tickers);

            for (String ticker : tickers) {
                try {
                    resultsServiceClient.triggerScreenerRefresh(ticker)
                            .subscribe(
                                    success -> {
                                        if (success) {
                                            log.info("Screener refresh completed for {}", ticker);
                                        }
                                    },
                                    error -> log.debug("Screener refresh failed for {}: {}", ticker, error.getMessage())
                            );
                    // Small delay to avoid overwhelming Screener
                    Thread.sleep(2000);
                } catch (Exception e) {
                    log.debug("Error refreshing {}: {}", ticker, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("Error in Screener refresh job: {}", e.getMessage());
        }
    }

    /**
     * Check if an announcement is a financial result.
     */
    private boolean isFinancialResultAnnouncement(Announcement ann) {
        if (ann == null || ann.getSubject() == null) {
            return false;
        }
        String subject = ann.getSubject().toLowerCase();
        String category = ann.getCategory() != null ? ann.getCategory().toLowerCase() : "";

        // Direct category match - "Result" category is always a financial result
        if (category.equals("result")) {
            return true;
        }

        String[] keywords = {"financial result", "quarterly result", "unaudited financial",
                "audited financial", "outcome of board meeting", "board meeting outcome",
                "board meeting", "results for the quarter", "results for quarter"};

        for (String keyword : keywords) {
            if (subject.contains(keyword) || category.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if today is a market day (weekday)
     */
    private boolean isMarketDay() {
        DayOfWeek today = LocalDate.now(ZoneId.of(timezone)).getDayOfWeek();
        return today != DayOfWeek.SATURDAY && today != DayOfWeek.SUNDAY;
    }

    /**
     * Check if current time is within market hours (9 AM - 4 PM IST)
     */
    private boolean isMarketHours() {
        LocalTime now = LocalTime.now(ZoneId.of(timezone));
        LocalTime marketOpen = LocalTime.of(9, 0);
        LocalTime marketClose = LocalTime.of(16, 0);
        return !now.isBefore(marketOpen) && !now.isAfter(marketClose);
    }
}
