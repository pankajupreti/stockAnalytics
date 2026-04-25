package com.example.alert_service.model;

/**
 * Status of an alert.
 */
public enum AlertStatus {
    /**
     * Alert is active and being monitored
     */
    ACTIVE,

    /**
     * Alert has been triggered
     */
    TRIGGERED,

    /**
     * Alert was cancelled by user
     */
    CANCELLED,

    /**
     * Alert expired (e.g., stock sold)
     */
    EXPIRED
}
