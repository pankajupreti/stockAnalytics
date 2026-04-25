package com.example.sheetimport.scheduler;

import com.example.sheetimport.service.YahooFinanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job to update 52-week high/low data from Yahoo Finance.
 * Runs daily after market close to update all stocks.
 */
@Component
public class YahooFinance52WeekJob {

    private static final Logger log = LoggerFactory.getLogger(YahooFinance52WeekJob.class);

    private final YahooFinanceService yahooFinanceService;

    @Value("${yahoo.finance.sync.enabled:true}")
    private boolean syncEnabled;

    public YahooFinance52WeekJob(YahooFinanceService yahooFinanceService) {
        this.yahooFinanceService = yahooFinanceService;
    }

    /**
     * Daily sync at 4:00 PM IST (after market close at 3:30 PM).
     * Updates 52-week high/low for all stocks.
     * Cron: second minute hour day-of-month month day-of-week
     */
    @Scheduled(cron = "${yahoo.finance.sync.cron:0 0 16 * * MON-FRI}", zone = "Asia/Kolkata")
    public void dailySync() {
        if (!syncEnabled) {
            log.debug("Yahoo Finance 52W sync is disabled");
            return;
        }

        log.info("Starting daily 52-week high/low sync from Yahoo Finance");
        try {
            int updated = yahooFinanceService.updateAll52WeekData();
            log.info("Daily 52W sync completed. Updated {} stocks", updated);
        } catch (Exception e) {
            log.error("Error during daily 52W sync: {}", e.getMessage(), e);
        }
    }

    /**
     * Hourly full sync during market hours (9 AM - 3 PM).
     * Updates all stocks every hour to keep 52W data fresh.
     * Market hours: 9:15 AM - 3:30 PM IST
     */
    @Scheduled(cron = "${yahoo.finance.sync.hourly-cron:0 0 9-15 * * MON-FRI}", zone = "Asia/Kolkata")
    public void hourlyMarketSync() {
        if (!syncEnabled) {
            return;
        }

        log.info("Running hourly 52W sync during market hours");
        try {
            int updated = yahooFinanceService.updateAll52WeekData();
            log.info("Hourly 52W sync completed. Updated {} stocks", updated);
        } catch (Exception e) {
            log.error("Error during hourly 52W sync: {}", e.getMessage(), e);
        }
    }

    /**
     * Weekend catch-up sync on Monday morning.
     * Ensures all stocks have fresh 52W data for the trading week.
     */
    @Scheduled(cron = "${yahoo.finance.sync.monday-cron:0 0 9 * * MON}", zone = "Asia/Kolkata")
    public void mondayMorningSyncup() {
        if (!syncEnabled) {
            return;
        }

        log.info("Starting Monday morning 52W catch-up sync");
        try {
            int updated = yahooFinanceService.updateAll52WeekData();
            log.info("Monday 52W sync completed. Updated {} stocks", updated);
        } catch (Exception e) {
            log.error("Error during Monday 52W sync: {}", e.getMessage(), e);
        }
    }
}
