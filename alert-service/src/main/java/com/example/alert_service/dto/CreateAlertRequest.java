package com.example.alert_service.dto;

import com.example.alert_service.model.AlertType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.math.BigDecimal;

/**
 * Request DTO for creating a new alert.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateAlertRequest {

    @NotBlank(message = "Ticker is required")
    private String ticker;

    private String companyName;

    @NotNull(message = "Alert type is required")
    private AlertType alertType;

    @NotNull(message = "Target price is required")
    @Positive(message = "Target price must be positive")
    private BigDecimal targetPrice;

    /**
     * Buy price (required for stop-loss alerts)
     */
    private BigDecimal buyPrice;

    /**
     * Stop-loss percentage (optional, defaults to 5%)
     */
    private BigDecimal stopLossPercent;

    /**
     * Notification channels: EMAIL, WHATSAPP, TELEGRAM
     */
    private String notificationChannels;

    /**
     * User's email for notifications
     */
    private String userEmail;

    /**
     * User's phone for WhatsApp/SMS
     */
    private String userPhone;

    /**
     * Telegram chat ID for Telegram notifications
     */
    private String telegramChatId;

    /**
     * Optional notes
     */
    private String notes;

    /**
     * Linked portfolio position ID
     */
    private Long positionId;
}
