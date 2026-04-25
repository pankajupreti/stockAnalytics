package com.example.sheetimport.repository;

import com.example.sheetimport.model.AnchorPriceCache;
import com.example.sheetimport.model.AnchorPriceCache.AnchorPriceCacheId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AnchorPriceCacheRepository extends JpaRepository<AnchorPriceCache, AnchorPriceCacheId> {

    /**
     * Find all prices for a specific date
     */
    @Query("SELECT a FROM AnchorPriceCache a WHERE a.id.priceDate = :date")
    List<AnchorPriceCache> findByDate(@Param("date") LocalDate date);

    /**
     * Check if prices exist for a date
     */
    @Query("SELECT COUNT(a) > 0 FROM AnchorPriceCache a WHERE a.id.priceDate = :date")
    boolean existsByDate(@Param("date") LocalDate date);

    /**
     * Count prices for a date
     */
    @Query("SELECT COUNT(a) FROM AnchorPriceCache a WHERE a.id.priceDate = :date")
    long countByDate(@Param("date") LocalDate date);

    /**
     * Get all cached dates
     */
    @Query("SELECT DISTINCT a.id.priceDate FROM AnchorPriceCache a ORDER BY a.id.priceDate DESC")
    List<LocalDate> findAllCachedDates();

    /**
     * Delete all prices for a date (for re-fetching)
     */
    @Query("DELETE FROM AnchorPriceCache a WHERE a.id.priceDate = :date")
    void deleteByDate(@Param("date") LocalDate date);
}
