package com.example.announcement_service.service;

import com.example.announcement_service.dto.ResultsFetchEvent;
import com.example.announcement_service.model.Announcement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Month;

/**
 * Publishes financial results events to RabbitMQ.
 * When a financial results announcement is detected, this service publishes
 * an event that triggers the Python results-service to fetch data from Screener.in.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResultsEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${results.rabbitmq.exchange}")
    private String exchange;

    @Value("${results.rabbitmq.routing-key}")
    private String routingKey;

    private static final int MAX_ATTEMPTS = 5;

    /**
     * Publish a results fetch event for a financial results announcement.
     *
     * @param announcement The announcement that triggered the event
     * @param ticker       The NSE ticker symbol
     */
    public void publishResultsFetchEvent(Announcement announcement, String ticker) {
        if (ticker == null || ticker.isBlank()) {
            log.warn("Cannot publish results fetch event: ticker is null or blank");
            return;
        }

        try {
            // Calculate expected quarter based on announcement date
            String expectedQuarter = calculateExpectedQuarter(announcement.getAnnouncementDate());

            ResultsFetchEvent event = ResultsFetchEvent.builder()
                    .ticker(ticker.toUpperCase())
                    .companyName(announcement.getCompanyName())
                    .announcementId(announcement.getId())
                    .newsId(announcement.getNewsId())
                    .announcementTime(announcement.getAnnouncementDate())
                    .eventTime(LocalDateTime.now())
                    .attemptNumber(1)
                    .maxAttempts(MAX_ATTEMPTS)
                    .subject(announcement.getSubject())
                    .expectedQuarter(expectedQuarter)
                    .build();

            rabbitTemplate.convertAndSend(exchange, routingKey, event);

            log.info("Published results fetch event for ticker: {} (announcement: {}, expectedQuarter: {})",
                    ticker, announcement.getNewsId(), expectedQuarter);

        } catch (Exception e) {
            log.error("Failed to publish results fetch event for ticker {}: {}",
                    ticker, e.getMessage(), e);
        }
    }

    /**
     * Publish a retry event (called by consumer when fetch fails).
     *
     * @param event The original event with incremented attempt number
     */
    public void publishRetryEvent(ResultsFetchEvent event) {
        if (event.getAttemptNumber() >= event.getMaxAttempts()) {
            log.warn("Max attempts ({}) reached for ticker {}, not retrying",
                    event.getMaxAttempts(), event.getTicker());
            return;
        }

        try {
            event.setAttemptNumber(event.getAttemptNumber() + 1);
            event.setEventTime(LocalDateTime.now());

            rabbitTemplate.convertAndSend(exchange, routingKey, event);

            log.info("Published retry event for ticker: {} (attempt {}/{})",
                    event.getTicker(), event.getAttemptNumber(), event.getMaxAttempts());

        } catch (Exception e) {
            log.error("Failed to publish retry event for ticker {}: {}",
                    event.getTicker(), e.getMessage(), e);
        }
    }

    /**
     * Calculate the expected quarter based on announcement date.
     * Indian fiscal year: April-March
     *
     * Results announcement timing (typical):
     * - Jan/Feb: Q3 results (Oct-Dec quarter)
     * - Apr/May: Q4 results (Jan-Mar quarter)
     * - Jul/Aug: Q1 results (Apr-Jun quarter)
     * - Oct/Nov: Q2 results (Jul-Sep quarter)
     *
     * @param announcementDate The date of the announcement
     * @return Expected quarter label (e.g., "Q3 FY2026")
     */
    private String calculateExpectedQuarter(LocalDateTime announcementDate) {
        if (announcementDate == null) {
            return null;
        }

        int month = announcementDate.getMonthValue();
        int year = announcementDate.getYear();

        String quarter;
        int fiscalYear;

        // Determine which quarter's results are being announced
        if (month >= 1 && month <= 3) {
            // Jan-Mar: Q3 results (Oct-Dec of previous calendar year)
            quarter = "Q3";
            fiscalYear = year; // FY ends in March of this year
        } else if (month >= 4 && month <= 6) {
            // Apr-Jun: Q4 results (Jan-Mar quarter)
            quarter = "Q4";
            fiscalYear = year; // FY just ended in March
        } else if (month >= 7 && month <= 9) {
            // Jul-Sep: Q1 results (Apr-Jun quarter)
            quarter = "Q1";
            fiscalYear = year + 1; // New FY started in April
        } else {
            // Oct-Dec: Q2 results (Jul-Sep quarter)
            quarter = "Q2";
            fiscalYear = year + 1; // FY that will end next March
        }

        return quarter + " FY" + fiscalYear;
    }
}
