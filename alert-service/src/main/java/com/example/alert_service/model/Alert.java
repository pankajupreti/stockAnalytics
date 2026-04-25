package com.example.alert_service.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Alert entity representing a price alert for a stock.
 */
@Entity
@Table(name = "alerts", indexes = {
        @Index(name = "idx_alerts_user_id", columnList = "userId"),
        @Index(name = "idx_alerts_ticker", columnList = "ticker"),
        @Index(name = "idx_alerts_status", columnList = "status"),
        @Index(name = "idx_alerts_user_ticker", columnList = "userId, ticker")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * User ID (from OAuth/JWT sub claim)
     */
    @Column(nullable = false, length = 128)
    private String userId;

    /**
     * User's email for notifications
     */
    @Column(length = 256)
    private String userEmail;

    /**
     * User's phone number for WhatsApp/SMS (with country code, e.g., +919876543210)
     */
    @Column(length = 20)
    private String userPhone;

    /**
     * User's Telegram chat ID for Telegram notifications
     */
    @Column(length = 64)
    private String telegramChatId;

    /**
     * Stock ticker (e.g., "NSE:TCS" or "TCS")
     */
    @Column(nullable = false, length = 32)
    private String ticker;

    /**
     * Company name for display
     */
    @Column(length = 128)
    private String companyName;

    /**
     * Alert type: STOP_LOSS, PRICE_ABOVE, PRICE_BELOW
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlertType alertType;

    /**
     * Target price that triggers the alert
     */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal targetPrice;

    /**
     * Buy price (used for stop-loss calculation)
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal buyPrice;

    /**
     * Percentage for stop-loss alerts (e.g., 5.0 for 5%)
     */
    @Column(precision = 5, scale = 2)
    private BigDecimal stopLossPercent;

    /**
     * Alert status: ACTIVE, TRIGGERED, CANCELLED
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlertStatus status;

    /**
     * Notification channels enabled for this alert
     */
    @Column(length = 50)
    private String notificationChannels; // Comma-separated: "EMAIL,WHATSAPP,TELEGRAM"

    /**
     * Price when alert was triggered
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal triggeredPrice;

    /**
     * When the alert was triggered
     */
    private LocalDateTime triggeredAt;

    /**
     * Whether notification was sent successfully
     */
    private Boolean notificationSent;

    /**
     * Whether user has seen/acknowledged this triggered alert (for in-app notification)
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean seen = false;

    /**
     * Optional notes/reason for the alert
     */
    @Column(length = 256)
    private String notes;

    /**
     * Linked portfolio position ID (if auto-created from portfolio)
     */
    private Long positionId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (status == null) {
            status = AlertStatus.ACTIVE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
