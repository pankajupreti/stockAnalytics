package com.example.announcement_service.repository;

import com.example.announcement_service.model.ParsedResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParsedResultRepository extends JpaRepository<ParsedResult, Long> {

    /**
     * Find all results for a ticker, ordered by fiscal year and quarter descending
     */
    @Query("SELECT r FROM ParsedResult r WHERE UPPER(r.ticker) = UPPER(:ticker) " +
           "ORDER BY r.fiscalYear DESC, " +
           "CASE r.quarter WHEN 'Q4' THEN 4 WHEN 'Q3' THEN 3 WHEN 'Q2' THEN 2 WHEN 'Q1' THEN 1 ELSE 0 END DESC")
    List<ParsedResult> findByTickerOrderByQuarterDesc(@Param("ticker") String ticker);

    /**
     * Find results for multiple tickers
     */
    @Query("SELECT r FROM ParsedResult r WHERE UPPER(r.ticker) IN :tickers " +
           "ORDER BY r.ticker, r.fiscalYear DESC, " +
           "CASE r.quarter WHEN 'Q4' THEN 4 WHEN 'Q3' THEN 3 WHEN 'Q2' THEN 2 WHEN 'Q1' THEN 1 ELSE 0 END DESC")
    List<ParsedResult> findByTickersOrderByQuarterDesc(@Param("tickers") List<String> tickers);

    /**
     * Find a specific quarter's result
     */
    Optional<ParsedResult> findByTickerIgnoreCaseAndQuarterAndFiscalYear(
            String ticker, String quarter, Integer fiscalYear);

    /**
     * Check if a result exists for a specific announcement
     */
    boolean existsByAnnouncementId(Long announcementId);

    /**
     * Find by announcement ID
     */
    Optional<ParsedResult> findByAnnouncementId(Long announcementId);

    /**
     * Find previous quarter result for QoQ calculation
     */
    @Query("SELECT r FROM ParsedResult r WHERE UPPER(r.ticker) = UPPER(:ticker) " +
           "AND ((r.fiscalYear = :fiscalYear AND r.quarter < :quarter) " +
           "     OR (r.fiscalYear = :fiscalYear - 1 AND r.quarter = 'Q4' AND :quarter = 'Q1')) " +
           "ORDER BY r.fiscalYear DESC, " +
           "CASE r.quarter WHEN 'Q4' THEN 4 WHEN 'Q3' THEN 3 WHEN 'Q2' THEN 2 WHEN 'Q1' THEN 1 ELSE 0 END DESC")
    List<ParsedResult> findPreviousQuarter(@Param("ticker") String ticker,
                                           @Param("quarter") String quarter,
                                           @Param("fiscalYear") Integer fiscalYear);

    /**
     * Find same quarter last year for YoY calculation
     */
    Optional<ParsedResult> findByTickerIgnoreCaseAndQuarterAndFiscalYear(
            String ticker, String quarter, int fiscalYear);

    /**
     * Get distinct tickers that have parsed results
     */
    @Query("SELECT DISTINCT r.ticker FROM ParsedResult r ORDER BY r.ticker")
    List<String> findDistinctTickers();

    /**
     * Count results by parse status
     */
    long countByParseStatus(ParsedResult.ParseStatus status);
}
