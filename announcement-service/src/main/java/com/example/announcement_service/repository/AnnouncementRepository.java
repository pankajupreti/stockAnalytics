package com.example.announcement_service.repository;

import com.example.announcement_service.model.Announcement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    Optional<Announcement> findByNewsId(String newsId);

    boolean existsByNewsId(String newsId);

    List<Announcement> findByTickerIgnoreCaseOrderByAnnouncementDateDesc(String ticker);

    List<Announcement> findByTickerIgnoreCaseAndAnnouncementDateAfterOrderByAnnouncementDateDesc(
            String ticker, LocalDateTime afterDate);

    @Query("SELECT a FROM Announcement a WHERE UPPER(a.ticker) IN :tickers ORDER BY a.announcementDate DESC")
    List<Announcement> findByTickersIn(@Param("tickers") List<String> tickers);

    @Query("SELECT a FROM Announcement a WHERE UPPER(a.ticker) IN :tickers AND a.announcementDate >= :afterDate ORDER BY a.announcementDate DESC")
    List<Announcement> findByTickersInAndAfterDate(
            @Param("tickers") List<String> tickers,
            @Param("afterDate") LocalDateTime afterDate);

    // NSE Ticker based queries (for matching with portfolio stocks)
    @Query("SELECT a FROM Announcement a WHERE UPPER(a.nseTicker) IN :nseTickers ORDER BY a.announcementDate DESC")
    List<Announcement> findByNseTickersIn(@Param("nseTickers") List<String> nseTickers);

    @Query("SELECT a FROM Announcement a WHERE UPPER(a.nseTicker) IN :nseTickers AND a.announcementDate >= :afterDate ORDER BY a.announcementDate DESC")
    List<Announcement> findByNseTickersInAndAfterDate(
            @Param("nseTickers") List<String> nseTickers,
            @Param("afterDate") LocalDateTime afterDate);

    @Query("SELECT a FROM Announcement a WHERE " +
            "(:category IS NULL OR a.category = :category) AND " +
            "a.announcementDate >= :fromDate AND a.announcementDate <= :toDate " +
            "ORDER BY a.announcementDate DESC")
    Page<Announcement> findByFilters(
            @Param("category") String category,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            Pageable pageable);

    @Query("SELECT a FROM Announcement a WHERE UPPER(a.ticker) IN :tickers AND " +
            "(:category IS NULL OR a.category = :category) AND " +
            "a.announcementDate >= :fromDate AND a.announcementDate <= :toDate " +
            "ORDER BY a.announcementDate DESC")
    Page<Announcement> findByTickersAndFilters(
            @Param("tickers") List<String> tickers,
            @Param("category") String category,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            Pageable pageable);

    @Query("SELECT DISTINCT a.category FROM Announcement a WHERE a.category IS NOT NULL ORDER BY a.category")
    List<String> findDistinctCategories();

    @Query("SELECT DISTINCT UPPER(a.ticker) FROM Announcement a WHERE a.announcementDate >= :afterDate")
    List<String> findTickersWithRecentAnnouncements(@Param("afterDate") LocalDateTime afterDate);

    @Query("SELECT DISTINCT UPPER(a.nseTicker) FROM Announcement a WHERE a.nseTicker IS NOT NULL AND a.announcementDate >= :afterDate")
    List<String> findNseTickersWithRecentAnnouncements(@Param("afterDate") LocalDateTime afterDate);

    /**
     * Search announcements by NSE ticker OR by matching the ticker column pattern.
     * This handles cases where nseTicker is null but ticker contains 'BSE:scripCode'.
     */
    @Query("SELECT a FROM Announcement a WHERE " +
            "(UPPER(a.nseTicker) IN :nseTickers OR UPPER(a.nseTicker) IN :fallbackTickers OR " +
            " UPPER(a.companyName) LIKE CONCAT('%', UPPER(:searchTerm), '%')) " +
            "AND a.announcementDate >= :afterDate ORDER BY a.announcementDate DESC")
    List<Announcement> findByTickersWithFallback(
            @Param("nseTickers") List<String> nseTickers,
            @Param("fallbackTickers") List<String> fallbackTickers,
            @Param("searchTerm") String searchTerm,
            @Param("afterDate") LocalDateTime afterDate);

    /**
     * Find announcements by company name (partial match, case-insensitive)
     */
    @Query("SELECT a FROM Announcement a WHERE UPPER(a.companyName) LIKE CONCAT('%', UPPER(:companyName), '%') AND a.announcementDate >= :afterDate ORDER BY a.announcementDate DESC")
    List<Announcement> findByCompanyNameContainingAndAfterDate(
            @Param("companyName") String companyName,
            @Param("afterDate") LocalDateTime afterDate);

    long countByTickerIgnoreCase(String ticker);

    long countByNseTickerIgnoreCase(String nseTicker);

    /**
     * Find announcements after a specific date (for scheduled Screener refresh).
     */
    List<Announcement> findByAnnouncementDateAfter(LocalDateTime afterDate);

    @Query("SELECT COUNT(DISTINCT a.ticker) FROM Announcement a WHERE UPPER(a.ticker) IN :tickers AND a.announcementDate >= :afterDate")
    long countTickersWithAnnouncements(@Param("tickers") List<String> tickers, @Param("afterDate") LocalDateTime afterDate);

    /**
     * Get distinct companies for autocomplete suggestions.
     * Returns company name, NSE ticker, and scrip code.
     */
    @Query("SELECT DISTINCT a.companyName, a.nseTicker, a.scripCode FROM Announcement a " +
            "WHERE (UPPER(a.companyName) LIKE CONCAT('%', UPPER(:query), '%') " +
            "OR UPPER(a.nseTicker) LIKE CONCAT('%', UPPER(:query), '%')) " +
            "AND a.companyName IS NOT NULL " +
            "ORDER BY a.companyName")
    List<Object[]> searchCompanies(@Param("query") String query);

    // ==================== SEEN/UNSEEN STATUS QUERIES ====================

    /**
     * Count unseen announcements for a user by NSE tickers
     */
    @Query("SELECT COUNT(a) FROM Announcement a WHERE " +
            "UPPER(a.nseTicker) IN :nseTickers AND " +
            "a.announcementDate >= :afterDate AND " +
            "(a.userId IS NULL OR a.userId = :userId) AND " +
            "(a.seen IS NULL OR a.seen = false)")
    long countUnseenByUserAndNseTickers(
            @Param("userId") String userId,
            @Param("nseTickers") List<String> nseTickers,
            @Param("afterDate") LocalDateTime afterDate);

    /**
     * Count unseen announcements per ticker for a user
     */
    @Query("SELECT a.nseTicker, COUNT(a) FROM Announcement a WHERE " +
            "UPPER(a.nseTicker) IN :nseTickers AND " +
            "a.announcementDate >= :afterDate AND " +
            "(a.userId IS NULL OR a.userId = :userId) AND " +
            "(a.seen IS NULL OR a.seen = false) " +
            "GROUP BY a.nseTicker")
    List<Object[]> countUnseenPerTickerByUser(
            @Param("userId") String userId,
            @Param("nseTickers") List<String> nseTickers,
            @Param("afterDate") LocalDateTime afterDate);

    /**
     * Find unseen announcements for a user by NSE tickers
     */
    @Query("SELECT a FROM Announcement a WHERE " +
            "UPPER(a.nseTicker) IN :nseTickers AND " +
            "a.announcementDate >= :afterDate AND " +
            "(a.userId IS NULL OR a.userId = :userId) AND " +
            "(a.seen IS NULL OR a.seen = false) " +
            "ORDER BY a.announcementDate DESC")
    List<Announcement> findUnseenByUserAndNseTickers(
            @Param("userId") String userId,
            @Param("nseTickers") List<String> nseTickers,
            @Param("afterDate") LocalDateTime afterDate);

    /**
     * Find announcements by newsIds for marking as seen
     */
    @Query("SELECT a FROM Announcement a WHERE a.newsId IN :newsIds")
    List<Announcement> findByNewsIds(@Param("newsIds") List<String> newsIds);

    /**
     * Find announcements by IDs
     */
    List<Announcement> findByIdIn(List<Long> ids);

    /**
     * Find announcements with scripCode but missing nseTicker mapping.
     * Returns scripCode, companyName, and count of announcements.
     * Used to identify gaps in ticker mappings.
     */
    @Query("SELECT a.scripCode, MAX(a.companyName), COUNT(a) FROM Announcement a " +
            "WHERE a.scripCode IS NOT NULL AND a.scripCode <> '' " +
            "AND (a.nseTicker IS NULL OR a.nseTicker = '') " +
            "AND a.announcementDate >= :afterDate " +
            "GROUP BY a.scripCode " +
            "ORDER BY COUNT(a) DESC")
    List<Object[]> findMissingMappings(@Param("afterDate") LocalDateTime afterDate);
}
