package com.example.reporting.repository;

import com.example.reporting.model.AnchorPriceCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository for anchor price cache (L2 persistent cache).
 */
@Repository
public interface AnchorPriceCacheRepository extends JpaRepository<AnchorPriceCache, AnchorPriceCache.AnchorPriceCacheId> {

    /**
     * Batch query - ONE query for all tickers on a specific date.
     * This is the main query used for anchor move calculations.
     */
    @Query("SELECT a FROM AnchorPriceCache a WHERE a.id.priceDate = :date AND a.id.ticker IN :tickers")
    List<AnchorPriceCache> findByDateAndTickers(
            @Param("date") LocalDate date,
            @Param("tickers") List<String> tickers
    );

    /**
     * Load all prices for a specific date.
     * Used for warming L1 cache when many users request the same popular date.
     */
    List<AnchorPriceCache> findByIdPriceDate(LocalDate priceDate);

    /**
     * Count cached prices for a date.
     * Useful for admin/monitoring.
     */
    long countByIdPriceDate(LocalDate priceDate);

    /**
     * Count cached prices for a date (alternative method name).
     */
    @Query("SELECT COUNT(a) FROM AnchorPriceCache a WHERE a.id.priceDate = :date")
    long countByDate(@Param("date") LocalDate date);

    /**
     * Get all cached dates.
     * Useful for admin/monitoring.
     */
    @Query("SELECT DISTINCT a.id.priceDate FROM AnchorPriceCache a ORDER BY a.id.priceDate DESC")
    List<LocalDate> findAllCachedDates();

    /**
     * Find nearest cached date on or before the given date.
     */
    @Query("SELECT DISTINCT a.id.priceDate FROM AnchorPriceCache a WHERE a.id.priceDate <= :date ORDER BY a.id.priceDate DESC LIMIT 1")
    LocalDate findNearestDateOnOrBefore(@Param("date") LocalDate date);
}
