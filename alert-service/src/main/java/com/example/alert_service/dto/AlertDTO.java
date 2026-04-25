package com.example.alert_service.dto;

import com.example.alert_service.model.AlertStatus;
import com.example.alert_service.model.AlertType;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for Alert responses.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertDTO {
    private Long id;
    private String ticker;
    private String companyName;
    private AlertType alertType;
    private BigDecimal targetPrice;
    private BigDecimal buyPrice;
    private BigDecimal stopLossPercent;
    private AlertStatus status;
    private String notificationChannels;
    private BigDecimal triggeredPrice;
    private LocalDateTime triggeredAt;
    private Boolean notificationSent;
    private Boolean seen;
    private String notes;
    private Long positionId;
    private LocalDateTime createdAt;

    // Current market price (enriched from reporting service)
    private BigDecimal currentPrice;
    // Distance to target (percentage)
    private BigDecimal distancePercent;
}
