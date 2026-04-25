package com.example.alert_service.controller;

import com.example.alert_service.dto.AlertDTO;
import com.example.alert_service.dto.CreateAlertRequest;
import com.example.alert_service.model.AlertType;
import com.example.alert_service.service.AlertMonitorService;
import com.example.alert_service.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Test controller for alert operations without authentication.
 * For development and testing only.
 */
@RestController
@RequestMapping("/api/test/alerts")
@RequiredArgsConstructor
@Slf4j
public class TestAlertController {

    private final AlertService alertService;
    private final AlertMonitorService alertMonitorService;

    private static final String TEST_USER_ID = "test-user-123";

    /**
     * Health check
     */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service", "alert-service"
        ));
    }

    /**
     * Get all alerts for test user.
     */
    @GetMapping
    public ResponseEntity<List<AlertDTO>> getAllAlerts() {
        return ResponseEntity.ok(alertService.getUserAlerts(TEST_USER_ID));
    }

    /**
     * Get active alerts.
     */
    @GetMapping("/active")
    public ResponseEntity<List<AlertDTO>> getActiveAlerts() {
        return ResponseEntity.ok(alertService.getActiveAlerts(TEST_USER_ID));
    }

    /**
     * Get triggered alerts (for notification bell).
     * Returns alerts that have been triggered, optionally filtered by 'unseen' status.
     */
    @GetMapping("/triggered")
    public ResponseEntity<List<AlertDTO>> getTriggeredAlerts(
            @RequestParam(defaultValue = "false") boolean unseenOnly) {
        return ResponseEntity.ok(alertService.getTriggeredAlerts(TEST_USER_ID, unseenOnly));
    }

    /**
     * Mark alerts as seen (user has viewed them).
     * POST /api/test/alerts/mark-seen
     * Body: { "alertIds": [1, 2, 3] }
     */
    @PostMapping("/mark-seen")
    public ResponseEntity<Map<String, Object>> markAlertsSeen(@RequestBody Map<String, List<Long>> request) {
        List<Long> alertIds = request.get("alertIds");
        if (alertIds == null || alertIds.isEmpty()) {
            return ResponseEntity.ok(Map.of("marked", 0));
        }
        int count = alertService.markAlertsSeen(TEST_USER_ID, alertIds);
        return ResponseEntity.ok(Map.of("marked", count));
    }

    /**
     * Get count of unseen triggered alerts (for notification badge).
     */
    @GetMapping("/unseen-count")
    public ResponseEntity<Map<String, Object>> getUnseenCount() {
        int count = alertService.getUnseenTriggeredCount(TEST_USER_ID);
        return ResponseEntity.ok(Map.of("count", count));
    }

    /**
     * Get alerts for a ticker.
     */
    @GetMapping("/ticker/{ticker}")
    public ResponseEntity<List<AlertDTO>> getAlertsForTicker(@PathVariable String ticker) {
        return ResponseEntity.ok(alertService.getAlertsForTicker(TEST_USER_ID, ticker));
    }

    /**
     * Create a test alert.
     * POST /api/test/alerts
     * Body: { "ticker": "TCS", "alertType": "PRICE_BELOW", "targetPrice": 4000, "userEmail": "test@example.com" }
     */
    @PostMapping
    public ResponseEntity<AlertDTO> createAlert(@RequestBody CreateAlertRequest request) {
        log.info("Creating test alert: {}", request);
        return ResponseEntity.ok(alertService.createAlert(TEST_USER_ID, request));
    }

    /**
     * Create a stop-loss alert.
     * POST /api/test/alerts/stop-loss
     */
    @PostMapping("/stop-loss")
    public ResponseEntity<AlertDTO> createStopLossAlert(@RequestBody Map<String, Object> request) {
        String ticker = (String) request.get("ticker");
        String companyName = (String) request.get("companyName");
        BigDecimal buyPrice = new BigDecimal(request.get("buyPrice").toString());
        Long positionId = request.get("positionId") != null ?
                Long.valueOf(request.get("positionId").toString()) : null;
        String userEmail = (String) request.getOrDefault("userEmail", "test@example.com");
        String userPhone = (String) request.get("userPhone");

        AlertDTO alert = alertService.createStopLossAlert(
                TEST_USER_ID, ticker, companyName, buyPrice, positionId, userEmail, userPhone);

        return ResponseEntity.ok(alert);
    }

    /**
     * Cancel an alert.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancelAlert(@PathVariable Long id) {
        if (alertService.cancelAlert(TEST_USER_ID, id)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Delete an alert.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAlert(@PathVariable Long id) {
        if (alertService.deleteAlert(TEST_USER_ID, id)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Manually trigger alert check for all active alerts.
     * POST /api/test/alerts/check-all
     */
    @PostMapping("/check-all")
    public ResponseEntity<Map<String, Object>> checkAllAlerts() {
        log.info("Manual trigger: checking all active alerts");
        alertMonitorService.checkAlerts();
        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "message", "Alert check completed"
        ));
    }

    /**
     * Check a specific alert.
     * POST /api/test/alerts/{id}/check
     */
    @PostMapping("/{id}/check")
    public ResponseEntity<Map<String, Object>> checkAlert(@PathVariable Long id) {
        boolean triggered = alertMonitorService.checkSingleAlert(id);
        return ResponseEntity.ok(Map.of(
                "alertId", id,
                "triggered", triggered
        ));
    }

    /**
     * Quick create: stop-loss for a stock.
     * GET /api/test/alerts/quick-sl?ticker=TCS&buyPrice=4200
     */
    @GetMapping("/quick-sl")
    public ResponseEntity<AlertDTO> quickStopLoss(
            @RequestParam String ticker,
            @RequestParam BigDecimal buyPrice,
            @RequestParam(defaultValue = "test@example.com") String email) {

        AlertDTO alert = alertService.createStopLossAlert(
                TEST_USER_ID, ticker, null, buyPrice, null, email, null);

        return ResponseEntity.ok(alert);
    }

    /**
     * Quick create: price alert.
     * GET /api/test/alerts/quick?ticker=TCS&target=4500&type=PRICE_ABOVE
     */
    @GetMapping("/quick")
    public ResponseEntity<AlertDTO> quickAlert(
            @RequestParam String ticker,
            @RequestParam BigDecimal target,
            @RequestParam(defaultValue = "PRICE_BELOW") AlertType type,
            @RequestParam(defaultValue = "test@example.com") String email) {

        CreateAlertRequest request = CreateAlertRequest.builder()
                .ticker(ticker)
                .alertType(type)
                .targetPrice(target)
                .userEmail(email)
                .notificationChannels("EMAIL")
                .build();

        return ResponseEntity.ok(alertService.createAlert(TEST_USER_ID, request));
    }
}
