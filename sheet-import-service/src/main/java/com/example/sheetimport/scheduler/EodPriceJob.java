package com.example.sheetimport.scheduler;

import com.example.sheetimport.service.EodPriceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Scheduled job to fetch EOD prices for all stocks daily.
 * Runs after market close to capture closing prices.
 */
@Component
public class EodPriceJob {

    private static final Logger log = LoggerFactory.getLogger(EodPriceJob.class);

    private final EodPriceService eodPriceService;

    @Value("${eod.price.job.enabled:true}")
    private boolean jobEnabled;

    public EodPriceJob(EodPriceService eodPriceService) {
        this.eodPriceService = eodPriceService;
    }

    /**
     * Run daily at 6:00 PM IST (after market close at 3:30 PM).
     * Only runs on weekdays (Mon-Fri).
     */
    @Scheduled(cron = "${eod.price.job.cron:0 0 18 * * MON-FRI}", zone = "Asia/Kolkata")
    public void fetchEodPrices() {
        if (!jobEnabled) {
            log.debug("EOD price job is disabled");
            return;
        }

        LocalDate today = LocalDate.now();
        log.info("Starting EOD price job for {}", today);

        try {
            java.util.Map<String, Object> result = eodPriceService.fetchPricesForDate(today, false);
            log.info("EOD price job completed: {}", result);
        } catch (Exception e) {
            log.error("EOD price job failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Manual trigger for testing.
     */
    public void triggerManually(LocalDate date) {
        log.info("Manual EOD price fetch triggered for {}", date);
        eodPriceService.fetchPricesForDate(date, false);
    }
}
