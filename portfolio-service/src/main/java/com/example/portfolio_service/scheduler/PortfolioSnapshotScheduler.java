package com.example.portfolio_service.scheduler;

import com.example.portfolio_service.service.PortfolioReturnsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job to capture daily portfolio snapshots for all users.
 * Runs after market close to capture accurate end-of-day values.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PortfolioSnapshotScheduler {

    private final PortfolioReturnsService returnsService;

    /**
     * Capture daily snapshots at 4:00 PM IST (after market close at 3:30 PM).
     * This ensures we have fresh closing prices from the 52W sync that runs at 4 PM.
     * Cron: second minute hour day-of-month month day-of-week
     */
    @Scheduled(cron = "0 5 16 * * MON-FRI", zone = "Asia/Kolkata")
    public void captureDailySnapshot() {
        log.info("Starting scheduled daily portfolio snapshot capture (after market close)");
        try {
            int count = returnsService.captureSnapshotsForAllUsers();
            log.info("Daily snapshot capture completed. Captured {} snapshots", count);
        } catch (Exception e) {
            log.error("Error in scheduled snapshot capture: {}", e.getMessage(), e);
        }
    }

    /**
     * Saturday morning catchup to cover any missed weekday snapshots.
     * Cron: 0 0 9 * * SAT = At 09:00 on Saturday
     */
    @Scheduled(cron = "0 0 9 * * SAT", zone = "Asia/Kolkata")
    public void captureWeekendCatchup() {
        log.info("Starting weekend catchup snapshot capture");
        try {
            int count = returnsService.captureSnapshotsForAllUsers();
            log.info("Weekend snapshot capture completed. Captured {} snapshots", count);
        } catch (Exception e) {
            log.error("Error in weekend snapshot capture: {}", e.getMessage(), e);
        }
    }
}
