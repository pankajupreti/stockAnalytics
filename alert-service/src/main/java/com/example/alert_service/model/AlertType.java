package com.example.alert_service.model;

/**
 * Types of price alerts.
 */
public enum AlertType {
    /**
     * Stop-loss alert - triggers when price falls below target
     */
    STOP_LOSS,

    /**
     * Price goes above target
     */
    PRICE_ABOVE,

    /**
     * Price goes below target
     */
    PRICE_BELOW
}
