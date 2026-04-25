package com.example.alert_service.repository;

import com.example.alert_service.model.Alert;
import com.example.alert_service.model.AlertStatus;
import com.example.alert_service.model.AlertType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AlertRepository extends JpaRepository<Alert, Long> {

    /**
     * Find all alerts for a user
     */
    List<Alert> findByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * Find active alerts for a user
     */
    List<Alert> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, AlertStatus status);

    /**
     * Find alerts for a specific ticker and user
     */
    List<Alert> findByUserIdAndTickerIgnoreCase(String userId, String ticker);

    /**
     * Find active alert for a specific ticker and user
     */
    Optional<Alert> findByUserIdAndTickerIgnoreCaseAndStatus(String userId, String ticker, AlertStatus status);

    /**
     * Find all active alerts (for monitoring)
     */
    List<Alert> findByStatus(AlertStatus status);

    /**
     * Find active alerts for specific tickers (for batch price check)
     */
    @Query("SELECT a FROM Alert a WHERE a.status = :status AND UPPER(a.ticker) IN :tickers")
    List<Alert> findByStatusAndTickersIn(@Param("status") AlertStatus status, @Param("tickers") List<String> tickers);

    /**
     * Get distinct tickers with active alerts
     */
    @Query("SELECT DISTINCT UPPER(a.ticker) FROM Alert a WHERE a.status = 'ACTIVE'")
    List<String> findDistinctActiveAlertTickers();

    /**
     * Find stop-loss alert for a position
     */
    Optional<Alert> findByPositionIdAndAlertType(Long positionId, AlertType alertType);

    /**
     * Count active alerts for a user
     */
    long countByUserIdAndStatus(String userId, AlertStatus status);

    /**
     * Find triggered alerts that haven't been notified
     */
    List<Alert> findByStatusAndNotificationSentFalse(AlertStatus status);

    /**
     * Find triggered alerts for a user, ordered by triggered time
     */
    List<Alert> findByUserIdAndStatusOrderByTriggeredAtDesc(String userId, AlertStatus status);

    /**
     * Find unseen triggered alerts for a user
     */
    List<Alert> findByUserIdAndStatusAndSeenFalseOrderByTriggeredAtDesc(String userId, AlertStatus status);

    /**
     * Count unseen triggered alerts for a user
     */
    int countByUserIdAndStatusAndSeenFalse(String userId, AlertStatus status);
}
