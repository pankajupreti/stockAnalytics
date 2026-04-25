package com.example.alert_service.controller;

import com.example.alert_service.dto.AlertDTO;
import com.example.alert_service.dto.CreateAlertRequest;
import com.example.alert_service.service.AlertMonitorService;
import com.example.alert_service.service.AlertService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * REST controller for managing alerts.
 */
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
@Slf4j
public class AlertController {

    private final AlertService alertService;
    private final AlertMonitorService alertMonitorService;

    /**
     * Get all alerts for the authenticated user.
     * GET /api/alerts
     */
    @GetMapping
    public ResponseEntity<List<AlertDTO>> getAllAlerts(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        return ResponseEntity.ok(alertService.getUserAlerts(userId));
    }

    /**
     * Get active alerts only.
     * GET /api/alerts/active
     */
    @GetMapping("/active")
    public ResponseEntity<List<AlertDTO>> getActiveAlerts(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        return ResponseEntity.ok(alertService.getActiveAlerts(userId));
    }

    /**
     * Get alerts for a specific ticker.
     * GET /api/alerts/ticker/{ticker}
     */
    @GetMapping("/ticker/{ticker}")
    public ResponseEntity<List<AlertDTO>> getAlertsForTicker(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String ticker) {
        String userId = jwt.getSubject();
        return ResponseEntity.ok(alertService.getAlertsForTicker(userId, ticker));
    }

    /**
     * Get a specific alert.
     * GET /api/alerts/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<AlertDTO> getAlert(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        String userId = jwt.getSubject();
        return alertService.getAlert(userId, id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new alert.
     * POST /api/alerts
     */
    @PostMapping
    public ResponseEntity<AlertDTO> createAlert(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateAlertRequest request) {
        String userId = jwt.getSubject();

        // Try to get email from JWT if not provided
        if (request.getUserEmail() == null) {
            request.setUserEmail(jwt.getClaimAsString("email"));
        }

        return ResponseEntity.ok(alertService.createAlert(userId, request));
    }

    /**
     * Create auto stop-loss alert (called from portfolio service when adding a stock).
     * POST /api/alerts/stop-loss
     */
    @PostMapping("/stop-loss")
    public ResponseEntity<AlertDTO> createStopLossAlert(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody Map<String, Object> request) {
        String userId = jwt.getSubject();
        String ticker = (String) request.get("ticker");
        String companyName = (String) request.get("companyName");
        BigDecimal buyPrice = new BigDecimal(request.get("buyPrice").toString());
        Long positionId = request.get("positionId") != null ?
                Long.valueOf(request.get("positionId").toString()) : null;
        String userEmail = (String) request.getOrDefault("userEmail", jwt.getClaimAsString("email"));
        String userPhone = (String) request.get("userPhone");

        AlertDTO alert = alertService.createStopLossAlert(
                userId, ticker, companyName, buyPrice, positionId, userEmail, userPhone);

        return ResponseEntity.ok(alert);
    }

    /**
     * Update an alert.
     * PUT /api/alerts/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<AlertDTO> updateAlert(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @RequestBody CreateAlertRequest request) {
        String userId = jwt.getSubject();
        return alertService.updateAlert(userId, id, request)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Cancel an alert (mark as cancelled but keep history).
     * POST /api/alerts/{id}/cancel
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancelAlert(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        String userId = jwt.getSubject();
        if (alertService.cancelAlert(userId, id)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Delete an alert permanently.
     * DELETE /api/alerts/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAlert(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        String userId = jwt.getSubject();
        if (alertService.deleteAlert(userId, id)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Get active alert counts per ticker (for portfolio UI).
     * GET /api/alerts/counts?tickers=TCS,RELIANCE
     */
    @GetMapping("/counts")
    public ResponseEntity<Map<String, Long>> getAlertCounts(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam List<String> tickers) {
        String userId = jwt.getSubject();
        return ResponseEntity.ok(alertService.getActiveAlertCounts(userId, tickers));
    }

    /**
     * Manually trigger alert check (for testing).
     * POST /api/alerts/{id}/check
     */
    @PostMapping("/{id}/check")
    public ResponseEntity<Map<String, Object>> checkAlert(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        boolean triggered = alertMonitorService.checkSingleAlert(id);
        return ResponseEntity.ok(Map.of(
                "alertId", id,
                "triggered", triggered
        ));
    }

    /**
     * Mark a single alert as seen/read.
     * POST /api/alerts/{id}/seen
     */
    @PostMapping("/{id}/seen")
    public ResponseEntity<Void> markAlertSeen(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id) {
        String userId = jwt.getSubject();
        alertService.markAlertsSeen(userId, List.of(id));
        return ResponseEntity.ok().build();
    }

    /**
     * Mark multiple alerts as seen/read.
     * POST /api/alerts/seen
     */
    @PostMapping("/seen")
    public ResponseEntity<Map<String, Object>> markAlertsSeen(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody Map<String, List<Long>> request) {
        String userId = jwt.getSubject();
        List<Long> alertIds = request.getOrDefault("alertIds", List.of());
        int count = alertService.markAlertsSeen(userId, alertIds);
        return ResponseEntity.ok(Map.of(
                "markedCount", count
        ));
    }

    /**
     * Get triggered alerts (for notifications UI).
     * GET /api/alerts/triggered?unseenOnly=true
     */
    @GetMapping("/triggered")
    public ResponseEntity<List<AlertDTO>> getTriggeredAlerts(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "false") boolean unseenOnly) {
        String userId = jwt.getSubject();
        return ResponseEntity.ok(alertService.getTriggeredAlerts(userId, unseenOnly));
    }

    /**
     * Get count of unseen triggered alerts.
     * GET /api/alerts/unseen-count
     */
    @GetMapping("/unseen-count")
    public ResponseEntity<Map<String, Integer>> getUnseenCount(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        int count = alertService.getUnseenTriggeredCount(userId);
        return ResponseEntity.ok(Map.of("count", count));
    }
}
