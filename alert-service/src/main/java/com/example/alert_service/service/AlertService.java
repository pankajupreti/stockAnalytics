package com.example.alert_service.service;

import com.example.alert_service.client.ReportingClient;
import com.example.alert_service.dto.AlertDTO;
import com.example.alert_service.dto.CreateAlertRequest;
import com.example.alert_service.dto.QuoteDTO;
import com.example.alert_service.model.Alert;
import com.example.alert_service.model.AlertStatus;
import com.example.alert_service.model.AlertType;
import com.example.alert_service.repository.AlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AlertService {

    private final AlertRepository alertRepository;
    private final ReportingClient reportingClient;

    @Value("${alert.default.stop-loss-percent:5.0}")
    private BigDecimal defaultStopLossPercent;

    /**
     * Get all alerts for a user, enriched with current prices.
     */
    public List<AlertDTO> getUserAlerts(String userId) {
        List<Alert> alerts = alertRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return enrichAlertsWithPrices(alerts);
    }

    /**
     * Get active alerts for a user.
     */
    public List<AlertDTO> getActiveAlerts(String userId) {
        List<Alert> alerts = alertRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, AlertStatus.ACTIVE);
        return enrichAlertsWithPrices(alerts);
    }

    /**
     * Get alerts for a specific ticker.
     */
    public List<AlertDTO> getAlertsForTicker(String userId, String ticker) {
        List<Alert> alerts = alertRepository.findByUserIdAndTickerIgnoreCase(userId, normalizeTickerForDb(ticker));
        return enrichAlertsWithPrices(alerts);
    }

    /**
     * Get alert by ID.
     */
    public Optional<AlertDTO> getAlert(String userId, Long alertId) {
        return alertRepository.findById(alertId)
                .filter(a -> a.getUserId().equals(userId))
                .map(this::toDTO);
    }

    /**
     * Create a new alert.
     */
    @Transactional
    public AlertDTO createAlert(String userId, CreateAlertRequest request) {
        String ticker = normalizeTickerForDb(request.getTicker());

        Alert alert = Alert.builder()
                .userId(userId)
                .userEmail(request.getUserEmail())
                .userPhone(request.getUserPhone())
                .telegramChatId(request.getTelegramChatId())
                .ticker(ticker)
                .companyName(request.getCompanyName())
                .alertType(request.getAlertType())
                .targetPrice(request.getTargetPrice())
                .buyPrice(request.getBuyPrice())
                .stopLossPercent(request.getStopLossPercent() != null ? request.getStopLossPercent() : defaultStopLossPercent)
                .status(AlertStatus.ACTIVE)
                .notificationChannels(request.getNotificationChannels() != null ? request.getNotificationChannels() : "EMAIL")
                .notes(request.getNotes())
                .positionId(request.getPositionId())
                .notificationSent(false)
                .build();

        Alert saved = alertRepository.save(alert);
        log.info("Created alert {} for user {} on ticker {} at target {}",
                saved.getId(), userId, ticker, request.getTargetPrice());

        return toDTO(saved);
    }

    /**
     * Create auto stop-loss alert when a stock is added to portfolio.
     * Target price = buyPrice * (1 - stopLossPercent/100)
     */
    @Transactional
    public AlertDTO createStopLossAlert(String userId, String ticker, String companyName,
                                         BigDecimal buyPrice, Long positionId,
                                         String userEmail, String userPhone) {
        String normalizedTicker = normalizeTickerForDb(ticker);

        // Check if stop-loss already exists for this position
        if (positionId != null) {
            Optional<Alert> existing = alertRepository.findByPositionIdAndAlertType(positionId, AlertType.STOP_LOSS);
            if (existing.isPresent()) {
                log.debug("Stop-loss alert already exists for position {}", positionId);
                return toDTO(existing.get());
            }
        }

        // Calculate stop-loss target: buyPrice * (1 - 5/100) = buyPrice * 0.95
        BigDecimal multiplier = BigDecimal.ONE.subtract(
                defaultStopLossPercent.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
        );
        BigDecimal targetPrice = buyPrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);

        Alert alert = Alert.builder()
                .userId(userId)
                .userEmail(userEmail)
                .userPhone(userPhone)
                .ticker(normalizedTicker)
                .companyName(companyName)
                .alertType(AlertType.STOP_LOSS)
                .targetPrice(targetPrice)
                .buyPrice(buyPrice)
                .stopLossPercent(defaultStopLossPercent)
                .status(AlertStatus.ACTIVE)
                .notificationChannels("EMAIL")
                .positionId(positionId)
                .notificationSent(false)
                .notes("Auto stop-loss at " + defaultStopLossPercent + "%")
                .build();

        Alert saved = alertRepository.save(alert);
        log.info("Created auto stop-loss alert {} for {} at {} (buy: {}, SL: {}%)",
                saved.getId(), normalizedTicker, targetPrice, buyPrice, defaultStopLossPercent);

        return toDTO(saved);
    }

    /**
     * Update an existing alert.
     */
    @Transactional
    public Optional<AlertDTO> updateAlert(String userId, Long alertId, CreateAlertRequest request) {
        return alertRepository.findById(alertId)
                .filter(a -> a.getUserId().equals(userId))
                .map(alert -> {
                    if (request.getTargetPrice() != null) {
                        alert.setTargetPrice(request.getTargetPrice());
                    }
                    if (request.getNotificationChannels() != null) {
                        alert.setNotificationChannels(request.getNotificationChannels());
                    }
                    if (request.getUserEmail() != null) {
                        alert.setUserEmail(request.getUserEmail());
                    }
                    if (request.getUserPhone() != null) {
                        alert.setUserPhone(request.getUserPhone());
                    }
                    if (request.getNotes() != null) {
                        alert.setNotes(request.getNotes());
                    }
                    return toDTO(alertRepository.save(alert));
                });
    }

    /**
     * Cancel an alert.
     */
    @Transactional
    public boolean cancelAlert(String userId, Long alertId) {
        return alertRepository.findById(alertId)
                .filter(a -> a.getUserId().equals(userId))
                .map(alert -> {
                    alert.setStatus(AlertStatus.CANCELLED);
                    alertRepository.save(alert);
                    log.info("Cancelled alert {} for user {}", alertId, userId);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Delete an alert.
     */
    @Transactional
    public boolean deleteAlert(String userId, Long alertId) {
        return alertRepository.findById(alertId)
                .filter(a -> a.getUserId().equals(userId))
                .map(alert -> {
                    alertRepository.delete(alert);
                    log.info("Deleted alert {} for user {}", alertId, userId);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Get count of active alerts per ticker for a user (for UI display).
     */
    public Map<String, Long> getActiveAlertCounts(String userId, List<String> tickers) {
        List<Alert> alerts = alertRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, AlertStatus.ACTIVE);

        return alerts.stream()
                .collect(Collectors.groupingBy(
                        a -> a.getTicker().toUpperCase(),
                        Collectors.counting()
                ));
    }

    /**
     * Normalize ticker for database storage.
     * Removes NSE:/BSE: prefix for consistent storage.
     */
    private String normalizeTickerForDb(String ticker) {
        if (ticker == null) return null;
        ticker = ticker.trim().toUpperCase();
        if (ticker.startsWith("NSE:")) return ticker.substring(4);
        if (ticker.startsWith("BSE:")) return ticker.substring(4);
        return ticker;
    }

    /**
     * Enrich alerts with current market prices.
     * Note: bearerToken can be null for test/internal calls (prices will be skipped)
     */
    private List<AlertDTO> enrichAlertsWithPrices(List<Alert> alerts, String bearerToken) {
        if (alerts.isEmpty()) {
            return List.of();
        }

        // If no token, just convert to DTOs without prices
        if (bearerToken == null || bearerToken.isBlank()) {
            return alerts.stream().map(this::toDTO).toList();
        }

        // Get unique tickers
        List<String> tickers = alerts.stream()
                .map(a -> "NSE:" + a.getTicker())  // Add NSE: prefix for reporting service
                .distinct()
                .toList();

        // Fetch current prices with JWT
        Map<String, QuoteDTO> quotes = reportingClient.getQuotes(tickers, bearerToken);

        // Enrich and convert to DTOs
        return alerts.stream()
                .map(alert -> {
                    AlertDTO dto = toDTO(alert);

                    // Add current price
                    String lookupKey = "NSE:" + alert.getTicker().toUpperCase();
                    QuoteDTO quote = quotes.get(lookupKey);
                    if (quote != null && quote.getCmp() != null) {
                        dto.setCurrentPrice(quote.getCmp());

                        // Calculate distance to target
                        if (alert.getTargetPrice() != null && quote.getCmp().compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal distance = alert.getTargetPrice()
                                    .subtract(quote.getCmp())
                                    .divide(quote.getCmp(), 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100))
                                    .setScale(2, RoundingMode.HALF_UP);
                            dto.setDistancePercent(distance);
                        }
                    }

                    return dto;
                })
                .toList();
    }

    /**
     * Overload for backwards compatibility (no token = no price enrichment)
     */
    private List<AlertDTO> enrichAlertsWithPrices(List<Alert> alerts) {
        return enrichAlertsWithPrices(alerts, null);
    }

    private AlertDTO toDTO(Alert alert) {
        return AlertDTO.builder()
                .id(alert.getId())
                .ticker(alert.getTicker())
                .companyName(alert.getCompanyName())
                .alertType(alert.getAlertType())
                .targetPrice(alert.getTargetPrice())
                .buyPrice(alert.getBuyPrice())
                .stopLossPercent(alert.getStopLossPercent())
                .status(alert.getStatus())
                .notificationChannels(alert.getNotificationChannels())
                .triggeredPrice(alert.getTriggeredPrice())
                .triggeredAt(alert.getTriggeredAt())
                .notificationSent(alert.getNotificationSent())
                .seen(alert.getSeen())
                .notes(alert.getNotes())
                .positionId(alert.getPositionId())
                .createdAt(alert.getCreatedAt())
                .build();
    }

    /**
     * Get triggered alerts for a user.
     * @param unseenOnly if true, only return alerts that haven't been seen
     */
    public List<AlertDTO> getTriggeredAlerts(String userId, boolean unseenOnly) {
        List<Alert> alerts;
        if (unseenOnly) {
            alerts = alertRepository.findByUserIdAndStatusAndSeenFalseOrderByTriggeredAtDesc(userId, AlertStatus.TRIGGERED);
        } else {
            alerts = alertRepository.findByUserIdAndStatusOrderByTriggeredAtDesc(userId, AlertStatus.TRIGGERED);
        }
        return alerts.stream().map(this::toDTO).toList();
    }

    /**
     * Mark alerts as seen by the user.
     */
    @Transactional
    public int markAlertsSeen(String userId, List<Long> alertIds) {
        int count = 0;
        for (Long alertId : alertIds) {
            alertRepository.findById(alertId)
                    .filter(a -> a.getUserId().equals(userId))
                    .ifPresent(alert -> {
                        alert.setSeen(true);
                        alertRepository.save(alert);
                    });
            count++;
        }
        return count;
    }

    /**
     * Get count of unseen triggered alerts.
     */
    public int getUnseenTriggeredCount(String userId) {
        return alertRepository.countByUserIdAndStatusAndSeenFalse(userId, AlertStatus.TRIGGERED);
    }
}
