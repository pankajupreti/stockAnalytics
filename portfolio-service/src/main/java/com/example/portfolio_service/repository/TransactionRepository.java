package com.example.portfolio_service.repository;

import com.example.portfolio_service.model.Transaction;
import com.example.portfolio_service.model.Transaction.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /** All transactions for a user, ordered by date desc */
    List<Transaction> findByUserSubOrderByTransactionDateDescCreatedAtDesc(String userSub);

    /** All transactions for a user and ticker */
    List<Transaction> findByUserSubAndTickerOrderByTransactionDateAscCreatedAtAsc(String userSub, String ticker);

    /** All BUY transactions for a user (for XIRR calculation) */
    List<Transaction> findByUserSubAndTypeOrderByTransactionDateAsc(String userSub, TransactionType type);

    /** All SELL transactions for a user (for P&L report) */
    @Query("SELECT t FROM Transaction t WHERE t.userSub = :userSub AND t.type = 'SELL' ORDER BY t.transactionDate DESC")
    List<Transaction> findSellTransactions(@Param("userSub") String userSub);

    /** All SELL transactions for a user within date range */
    @Query("SELECT t FROM Transaction t WHERE t.userSub = :userSub AND t.type = 'SELL' " +
           "AND t.transactionDate >= :fromDate AND t.transactionDate <= :toDate " +
           "ORDER BY t.transactionDate DESC")
    List<Transaction> findSellTransactionsInRange(
            @Param("userSub") String userSub,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /** Sum of realized P&L for a user */
    @Query("SELECT COALESCE(SUM(t.realizedPnl), 0) FROM Transaction t WHERE t.userSub = :userSub AND t.type = 'SELL'")
    java.math.BigDecimal getTotalRealizedPnl(@Param("userSub") String userSub);

    /** Sum of all sell proceeds (price × quantity for all SELL transactions) - this is the actual cash received */
    @Query("SELECT COALESCE(SUM(t.price * t.quantity), 0) FROM Transaction t WHERE t.userSub = :userSub AND t.type = 'SELL'")
    java.math.BigDecimal getTotalSellProceeds(@Param("userSub") String userSub);

    /** Sum of sell proceeds up to a specific date (for historical snapshot recalculation) */
    @Query("SELECT COALESCE(SUM(t.price * t.quantity), 0) FROM Transaction t WHERE t.userSub = :userSub AND t.type = 'SELL' AND t.transactionDate <= :asOfDate")
    java.math.BigDecimal getSellProceedsAsOfDate(@Param("userSub") String userSub, @Param("asOfDate") java.time.LocalDate asOfDate);

    /** Sum of realized P&L up to a specific date */
    @Query("SELECT COALESCE(SUM(t.realizedPnl), 0) FROM Transaction t WHERE t.userSub = :userSub AND t.type = 'SELL' AND t.transactionDate <= :asOfDate")
    java.math.BigDecimal getRealizedPnlAsOfDate(@Param("userSub") String userSub, @Param("asOfDate") java.time.LocalDate asOfDate);

    /** Sum of realized P&L for a user within date range */
    @Query("SELECT COALESCE(SUM(t.realizedPnl), 0) FROM Transaction t WHERE t.userSub = :userSub AND t.type = 'SELL' " +
           "AND t.transactionDate >= :fromDate AND t.transactionDate <= :toDate")
    java.math.BigDecimal getRealizedPnlInRange(
            @Param("userSub") String userSub,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /** All transactions for XIRR calculation (both BUY and SELL) */
    List<Transaction> findByUserSubOrderByTransactionDateAscCreatedAtAsc(String userSub);

    /** Check if position has any transactions */
    boolean existsByPositionId(Long positionId);

    /** Batch get buy dates for a set of positionIds (for holding days calculation) */
    @Query("SELECT t.positionId, MIN(t.transactionDate) FROM Transaction t " +
           "WHERE t.positionId IN :positionIds AND t.type = 'BUY' GROUP BY t.positionId")
    List<Object[]> findBuyDatesByPositionIds(@Param("positionIds") List<Long> positionIds);

    /** BUY transactions for a ticker, ordered by date (for buy date lookup when positionId is null) */
    List<Transaction> findByTickerAndTypeOrderByTransactionDateAsc(String ticker, TransactionType type);

    /** Sum of all BUY transaction values (price × quantity) up to a specific date */
    @Query("SELECT COALESCE(SUM(t.price * t.quantity), 0) FROM Transaction t WHERE t.userSub = :userSub AND t.type = 'BUY' AND t.transactionDate <= :asOfDate")
    java.math.BigDecimal getTotalInvestedAsOfDate(@Param("userSub") String userSub, @Param("asOfDate") java.time.LocalDate asOfDate);

    /** Sum of all BUY transaction values by created_at date (when actually added to system).
     *  Used for chart recalculation so capital is attributed to the snapshot date when the stock's
     *  market value first appears, not the historical buy date. */
    @Query("SELECT COALESCE(SUM(t.price * t.quantity), 0) FROM Transaction t WHERE t.userSub = :userSub AND t.type = 'BUY' AND CAST(t.createdAt AS date) <= :asOfDate")
    java.math.BigDecimal getTotalInvestedByCreatedDate(@Param("userSub") String userSub, @Param("asOfDate") java.time.LocalDate asOfDate);
}
