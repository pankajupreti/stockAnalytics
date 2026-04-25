package com.example.alert_service.service;

import com.example.alert_service.client.ReportingClient;
import com.example.alert_service.dto.QuoteDTO;
import com.example.alert_service.model.Alert;
import com.example.alert_service.model.AlertStatus;
import com.example.alert_service.model.AlertType;
import com.example.alert_service.notification.NotificationService;
import com.example.alert_service.repository.AlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Service that monitors prices and triggers alerts.
 * Runs on a schedule to check active alerts against current prices.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertMonitorService {

    private final AlertRepository alertRepository;
    private final ReportingClient reportingClient;
    private final NotificationService notificationService;

    @Value("${alert.monitor.enabled:true}")
    private boolean monitorEnabled;

    /**
     * Check active alerts against current prices.
     * Runs every minute by default.
     */
    @Scheduled(fixedRateString = "${alert.monitor.interval-ms:60000}")
    @Transactional
    public void checkAlerts() {
        if (!monitorEnabled) {
            return;
        }

        // Get all active alerts
        List<Alert> activeAlerts = alertRepository.findByStatus(AlertStatus.ACTIVE);
        if (activeAlerts.isEmpty()) {
            log.debug("No active alerts to check");
            return;
        }

        log.info("Checking {} active alerts", activeAlerts.size());

        // Get unique tickers
        List<String> tickers = activeAlerts.stream()
                .map(a -> "NSE:" + a.getTicker().toUpperCase())
                .distinct()
                .toList();

        // Fetch current prices using internal endpoint (no JWT needed for scheduled tasks)
        Map<String, QuoteDTO> quotes = reportingClient.getQuotesInternal(tickers);
        if (quotes.isEmpty()) {
            log.warn("No quotes returned from reporting service");
            return;
        }

        int triggeredCount = 0;

        for (Alert alert : activeAlerts) {
            String lookupKey = "NSE:" + alert.getTicker().toUpperCase();
            QuoteDTO quote = quotes.get(lookupKey);

            if (quote == null || quote.getCmp() == null) {
                log.debug("No price available for {}", alert.getTicker());
                continue;
            }

            BigDecimal currentPrice = quote.getCmp();
            boolean shouldTrigger = checkTriggerCondition(alert, currentPrice);

            if (shouldTrigger) {
                triggerAlert(alert, currentPrice);
                triggeredCount++;
            }
        }

        if (triggeredCount > 0) {
            log.info("Triggered {} alerts", triggeredCount);
        }
    }

    /**
     * Check if an alert should be triggered based on current price.
     */
    private boolean checkTriggerCondition(Alert alert, BigDecimal currentPrice) {
        BigDecimal targetPrice = alert.getTargetPrice();
        if (targetPrice == null) {
            return false;
        }

        switch (alert.getAlertType()) {
            case STOP_LOSS:
            case PRICE_BELOW:
                // Trigger when price falls to or below target
                return currentPrice.compareTo(targetPrice) <= 0;

            case PRICE_ABOVE:
                // Trigger when price rises to or above target
                return currentPrice.compareTo(targetPrice) >= 0;

            default:
                return false;
        }
    }

    /**
     * Trigger an alert - update status and send notifications.
     */
    private void triggerAlert(Alert alert, BigDecimal currentPrice) {
        log.info("ALERT TRIGGERED: {} {} at {} (target: {}, type: {})",
                alert.getTicker(),
                alert.getAlertType(),
                currentPrice,
                alert.getTargetPrice(),
                alert.getAlertType());

        // Update alert status
        alert.setStatus(AlertStatus.TRIGGERED);
        alert.setTriggeredPrice(currentPrice);
        alert.setTriggeredAt(LocalDateTime.now());
        alertRepository.save(alert);

        // Send notifications asynchronously
        try {
            notificationService.sendAlertNotification(alert, currentPrice);
            alert.setNotificationSent(true);
            alertRepository.save(alert);
        } catch (Exception e) {
            log.error("Failed to send notification for alert {}: {}", alert.getId(), e.getMessage());
            alert.setNotificationSent(false);
            alertRepository.save(alert);
        }
    }

    /**
     * Retry sending notifications for triggered alerts that failed.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    @Transactional
    public void retryFailedNotifications() {
        if (!monitorEnabled) {
            return;
        }

        List<Alert> failedAlerts = alertRepository.findByStatusAndNotificationSentFalse(AlertStatus.TRIGGERED);
        if (failedAlerts.isEmpty()) {
            return;
        }

        log.info("Retrying {} failed notifications", failedAlerts.size());

        for (Alert alert : failedAlerts) {
            try {
                notificationService.sendAlertNotification(alert, alert.getTriggeredPrice());
                alert.setNotificationSent(true);
                alertRepository.save(alert);
                log.info("Successfully retried notification for alert {}", alert.getId());
            } catch (Exception e) {
                log.warn("Retry failed for alert {}: {}", alert.getId(), e.getMessage());
            }
        }
    }

    /**
     * Manual trigger for testing - check a specific alert.
     * Uses internal endpoint (no JWT needed for test/manual triggers).
     */
    @Transactional
    public boolean checkSingleAlert(Long alertId) {
        return alertRepository.findById(alertId)
                .filter(a -> a.getStatus() == AlertStatus.ACTIVE)
                .map(alert -> {
                    String lookupKey = "NSE:" + alert.getTicker().toUpperCase();
                    Map<String, QuoteDTO> quotes = reportingClient.getQuotesInternal(List.of(lookupKey));
                    QuoteDTO quote = quotes.get(lookupKey);

                    if (quote != null && quote.getCmp() != null) {
                        if (checkTriggerCondition(alert, quote.getCmp())) {
                            triggerAlert(alert, quote.getCmp());
                            return true;
                        }
                    }
                    return false;
                })
                .orElse(false);
    }
}
