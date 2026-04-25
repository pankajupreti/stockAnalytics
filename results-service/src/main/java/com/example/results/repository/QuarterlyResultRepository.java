package com.example.results.repository;

import com.example.results.model.QuarterlyResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QuarterlyResultRepository extends JpaRepository<QuarterlyResult, Long> {

    /**
     * Find all results for a ticker, ordered by fiscal year and quarter descending (most recent first).
     */
    @Query("SELECT q FROM QuarterlyResult q WHERE q.ticker = :ticker " +
           "ORDER BY q.fiscalYear DESC, " +
           "CASE q.quarter WHEN 'Q4' THEN 4 WHEN 'Q3' THEN 3 WHEN 'Q2' THEN 2 WHEN 'Q1' THEN 1 ELSE 0 END DESC")
    List<QuarterlyResult> findByTickerOrderByQuarterDesc(@Param("ticker") String ticker);

    /**
     * Find specific quarter result for a ticker.
     */
    Optional<QuarterlyResult> findByTickerAndQuarterAndFiscalYear(String ticker, String quarter, Integer fiscalYear);

    /**
     * Find latest result for a ticker.
     */
    @Query("SELECT q FROM QuarterlyResult q WHERE q.ticker = :ticker " +
           "ORDER BY q.fiscalYear DESC, " +
           "CASE q.quarter WHEN 'Q4' THEN 4 WHEN 'Q3' THEN 3 WHEN 'Q2' THEN 2 WHEN 'Q1' THEN 1 ELSE 0 END DESC " +
           "LIMIT 1")
    Optional<QuarterlyResult> findLatestByTicker(@Param("ticker") String ticker);

    /**
     * Find results for multiple tickers (for portfolio view).
     */
    @Query("SELECT q FROM QuarterlyResult q WHERE q.ticker IN :tickers " +
           "ORDER BY q.ticker, q.fiscalYear DESC, " +
           "CASE q.quarter WHEN 'Q4' THEN 4 WHEN 'Q3' THEN 3 WHEN 'Q2' THEN 2 WHEN 'Q1' THEN 1 ELSE 0 END DESC")
    List<QuarterlyResult> findByTickerIn(@Param("tickers") List<String> tickers);

    /**
     * Find latest results for multiple tickers (one per ticker).
     */
    @Query(value = "SELECT DISTINCT ON (ticker) * FROM quarterly_results " +
                   "WHERE ticker IN :tickers " +
                   "ORDER BY ticker, fiscal_year DESC, " +
                   "CASE quarter WHEN 'Q4' THEN 4 WHEN 'Q3' THEN 3 WHEN 'Q2' THEN 2 WHEN 'Q1' THEN 1 ELSE 0 END DESC",
           nativeQuery = true)
    List<QuarterlyResult> findLatestByTickerIn(@Param("tickers") List<String> tickers);

    /**
     * Find results by announcement ID.
     */
    Optional<QuarterlyResult> findByAnnouncementId(Long announcementId);

    /**
     * Check if result exists for announcement.
     */
    boolean existsByAnnouncementId(Long announcementId);

    /**
     * Find previous quarter result for calculating QoQ.
     */
    @Query("SELECT q FROM QuarterlyResult q WHERE q.ticker = :ticker AND " +
           "(q.fiscalYear < :fiscalYear OR (q.fiscalYear = :fiscalYear AND " +
           "CASE q.quarter WHEN 'Q4' THEN 4 WHEN 'Q3' THEN 3 WHEN 'Q2' THEN 2 WHEN 'Q1' THEN 1 ELSE 0 END < " +
           "CASE :quarter WHEN 'Q4' THEN 4 WHEN 'Q3' THEN 3 WHEN 'Q2' THEN 2 WHEN 'Q1' THEN 1 ELSE 0 END)) " +
           "ORDER BY q.fiscalYear DESC, " +
           "CASE q.quarter WHEN 'Q4' THEN 4 WHEN 'Q3' THEN 3 WHEN 'Q2' THEN 2 WHEN 'Q1' THEN 1 ELSE 0 END DESC " +
           "LIMIT 1")
    Optional<QuarterlyResult> findPreviousQuarter(@Param("ticker") String ticker,
                                                   @Param("fiscalYear") Integer fiscalYear,
                                                   @Param("quarter") String quarter);

    /**
     * Find same quarter last year for calculating YoY.
     */
    Optional<QuarterlyResult> findByTickerAndQuarterAndFiscalYearLessThan(
            String ticker, String quarter, Integer fiscalYear);

    /**
     * Count results by parse status.
     */
    long countByParseStatus(QuarterlyResult.ParseStatus status);
}
