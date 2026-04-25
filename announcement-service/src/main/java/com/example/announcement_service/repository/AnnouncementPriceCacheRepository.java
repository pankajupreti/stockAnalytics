package com.example.announcement_service.repository;

import com.example.announcement_service.model.AnnouncementPriceCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for announcement price cache.
 */
@Repository
public interface AnnouncementPriceCacheRepository extends JpaRepository<AnnouncementPriceCache, Long> {

    /**
     * Find cached price for a ticker on a specific date.
     */
    Optional<AnnouncementPriceCache> findByTickerAndPriceDate(String ticker, LocalDate priceDate);

    /**
     * Find all cached prices for a ticker.
     */
    List<AnnouncementPriceCache> findByTicker(String ticker);

    /**
     * Find all cached prices for multiple tickers on specific dates.
     * Useful for bulk lookups.
     */
    @Query("SELECT c FROM AnnouncementPriceCache c WHERE c.ticker IN :tickers AND c.priceDate IN :dates")
    List<AnnouncementPriceCache> findByTickersAndDates(
            @Param("tickers") List<String> tickers,
            @Param("dates") List<LocalDate> dates);

    /**
     * Find all cached prices for multiple tickers (for bulk loading).
     */
    @Query("SELECT c FROM AnnouncementPriceCache c WHERE c.ticker IN :tickers")
    List<AnnouncementPriceCache> findByTickers(@Param("tickers") List<String> tickers);

    /**
     * Check if a price is already cached.
     */
    boolean existsByTickerAndPriceDate(String ticker, LocalDate priceDate);

    /**
     * Delete old cache entries (for cleanup).
     */
    @Query("DELETE FROM AnnouncementPriceCache c WHERE c.fetchedAt < :before")
    void deleteOlderThan(@Param("before") java.time.LocalDateTime before);
}
