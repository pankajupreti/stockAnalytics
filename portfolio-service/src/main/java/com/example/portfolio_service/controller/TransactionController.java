package com.example.portfolio_service.controller;

import com.example.portfolio_service.dto.AddSharesRequest;
import com.example.portfolio_service.dto.SellRequest;
import com.example.portfolio_service.dto.TransactionDTO;
import com.example.portfolio_service.model.Transaction;
import com.example.portfolio_service.repository.TransactionRepository;
import com.example.portfolio_service.security.CurrentUser;
import com.example.portfolio_service.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
@RequestMapping("/api/portfolio/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;
    private final TransactionRepository transactionRepository;
    private final CurrentUser currentUser;

    /**
     * Add more shares to existing position or create new position
     */
    @PostMapping("/add")
    public ResponseEntity<TransactionDTO> addShares(
            Authentication auth,
            @RequestBody AddSharesRequest req) {
        String userSub = currentUser.sub(auth);
        Transaction tx = transactionService.addShares(userSub, req);
        return ResponseEntity.ok(toDTO(tx));
    }

    /**
     * Sell shares from existing position
     */
    @PostMapping("/sell")
    public ResponseEntity<?> sellShares(
            Authentication auth,
            @RequestBody SellRequest req) {
        try {
            String userSub = currentUser.sub(auth);
            Transaction tx = transactionService.sellShares(userSub, req);
            return ResponseEntity.ok(toDTO(tx));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get all transactions (BUY and SELL)
     */
    @GetMapping
    public List<TransactionDTO> getAllTransactions(Authentication auth) {
        return transactionService.getTransactions(currentUser.sub(auth));
    }

    /**
     * Get only SELL transactions (for P&L report)
     */
    @GetMapping("/sells")
    public List<TransactionDTO> getSellTransactions(Authentication auth) {
        return transactionService.getSellTransactions(currentUser.sub(auth));
    }

    /**
     * Get SELL transactions within date range
     */
    @GetMapping("/sells/range")
    public List<TransactionDTO> getSellTransactionsInRange(
            Authentication auth,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return transactionService.getSellTransactionsInRange(currentUser.sub(auth), from, to);
    }

    /**
     * Migrate existing positions to transactions (one-time migration)
     * This creates BUY transactions for all existing positions that don't have transactions
     */
    @PostMapping("/migrate")
    public ResponseEntity<Map<String, Object>> migratePositions(Authentication auth) {
        String userSub = currentUser.sub(auth);
        int migrated = transactionService.migrateAllPositions(userSub);
        return ResponseEntity.ok(Map.of(
                "message", "Migration completed",
                "migratedPositions", migrated
        ));
    }

    /**
     * Get P&L summary with comprehensive trade statistics
     */
    @GetMapping("/pnl-summary")
    public Map<String, Object> getPnlSummary(
            Authentication auth,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        String userSub = currentUser.sub(auth);
        BigDecimal totalRealizedPnl;
        List<TransactionDTO> sells;

        if (from != null && to != null) {
            totalRealizedPnl = transactionService.getRealizedPnlInRange(userSub, from, to);
            sells = transactionService.getSellTransactionsInRange(userSub, from, to);
        } else {
            totalRealizedPnl = transactionService.getTotalRealizedPnl(userSub);
            sells = transactionService.getSellTransactions(userSub);
        }

        long winningTrades = 0;
        long losingTrades = 0;
        BigDecimal totalProfit = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;

        // Extended stats
        List<Double> gainPcts = new ArrayList<>();
        List<Double> lossPcts = new ArrayList<>();
        BigDecimal maxPnl = BigDecimal.ZERO;
        BigDecimal minPnl = BigDecimal.ZERO;
        BigDecimal sumCapitalOnWins = BigDecimal.ZERO;

        // Batch-load buy dates for holding days
        List<Long> positionIds = sells.stream()
                .map(TransactionDTO::getPositionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, LocalDate> buyDateMap = new HashMap<>();
        if (!positionIds.isEmpty()) {
            List<Object[]> rows = transactionRepository.findBuyDatesByPositionIds(positionIds);
            for (Object[] row : rows) {
                Long posId = (Long) row[0];
                LocalDate buyDate = (LocalDate) row[1];
                buyDateMap.put(posId, buyDate);
            }
        }

        long totalHoldingDays = 0;
        int holdingDaysCount = 0;

        for (TransactionDTO s : sells) {
            if (s.getRealizedPnl() == null) continue;

            BigDecimal pnl = s.getRealizedPnl();

            if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                winningTrades++;
                totalProfit = totalProfit.add(pnl);
            } else if (pnl.compareTo(BigDecimal.ZERO) < 0) {
                losingTrades++;
                totalLoss = totalLoss.add(pnl);
            }

            // Track absolute extremes
            if (pnl.compareTo(maxPnl) > 0) maxPnl = pnl;
            if (pnl.compareTo(minPnl) < 0) minPnl = pnl;

            // Per-trade P&L percentage
            if (s.getAvgBuyPriceAtSell() != null && s.getQuantity() != null && s.getQuantity() > 0) {
                BigDecimal buyCost = s.getAvgBuyPriceAtSell()
                        .multiply(BigDecimal.valueOf(s.getQuantity()));

                if (buyCost.compareTo(BigDecimal.ZERO) > 0) {
                    double pct = pnl.divide(buyCost, 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .doubleValue();

                    if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                        gainPcts.add(pct);
                        sumCapitalOnWins = sumCapitalOnWins.add(buyCost);
                    } else if (pnl.compareTo(BigDecimal.ZERO) < 0) {
                        lossPcts.add(pct);
                    }
                }
            }

            // Holding days
            if (s.getPositionId() != null && s.getTransactionDate() != null) {
                LocalDate buyDate = buyDateMap.get(s.getPositionId());
                if (buyDate != null) {
                    long days = ChronoUnit.DAYS.between(buyDate, s.getTransactionDate());
                    if (days >= 0) {
                        totalHoldingDays += days;
                        holdingDaysCount++;
                    }
                }
            }
        }

        // Derived stats
        double avgGainPct = gainPcts.isEmpty() ? 0 :
                gainPcts.stream().mapToDouble(d -> d).average().orElse(0);
        double avgLossPct = lossPcts.isEmpty() ? 0 :
                lossPcts.stream().mapToDouble(d -> d).average().orElse(0);
        double largestWinPct = gainPcts.isEmpty() ? 0 :
                gainPcts.stream().mapToDouble(d -> d).max().orElse(0);
        double largestLossPct = lossPcts.isEmpty() ? 0 :
                lossPcts.stream().mapToDouble(d -> d).min().orElse(0);

        BigDecimal avgGainPerTrade = winningTrades > 0 ?
                totalProfit.divide(BigDecimal.valueOf(winningTrades), 2, RoundingMode.HALF_UP) :
                BigDecimal.ZERO;
        BigDecimal avgLossPerTrade = losingTrades > 0 ?
                totalLoss.divide(BigDecimal.valueOf(losingTrades), 2, RoundingMode.HALF_UP) :
                BigDecimal.ZERO;
        BigDecimal totalAvg = sells.isEmpty() ? BigDecimal.ZERO :
                totalRealizedPnl.divide(BigDecimal.valueOf(sells.size()), 2, RoundingMode.HALF_UP);

        BigDecimal profitFactor = (totalLoss.compareTo(BigDecimal.ZERO) != 0) ?
                totalProfit.divide(totalLoss.abs(), 2, RoundingMode.HALF_UP) :
                BigDecimal.ZERO;

        double pnlRatio = (avgLossPerTrade.compareTo(BigDecimal.ZERO) != 0) ?
                avgGainPerTrade.doubleValue() / Math.abs(avgLossPerTrade.doubleValue()) : 0;

        BigDecimal avgCapPerGains = !gainPcts.isEmpty() ?
                sumCapitalOnWins.divide(BigDecimal.valueOf(gainPcts.size()), 2, RoundingMode.HALF_UP) :
                BigDecimal.ZERO;

        Double avgHoldingDays = holdingDaysCount > 0 ?
                (double) totalHoldingDays / holdingDaysCount : null;

        Map<String, Object> result = new HashMap<>();
        // Original 7 stats
        result.put("totalRealizedPnl", totalRealizedPnl);
        result.put("totalTrades", sells.size());
        result.put("winningTrades", winningTrades);
        result.put("losingTrades", losingTrades);
        result.put("totalProfit", totalProfit);
        result.put("totalLoss", totalLoss);
        result.put("winRate", sells.isEmpty() ? BigDecimal.ZERO :
                BigDecimal.valueOf(winningTrades * 100.0 / sells.size()));
        // Extended stats
        result.put("avgGainPct", avgGainPct);
        result.put("avgLossPct", avgLossPct);
        result.put("largestWinPct", largestWinPct);
        result.put("largestLossPct", largestLossPct);
        result.put("profitFactor", profitFactor);
        result.put("pnlRatio", Math.round(pnlRatio * 100.0) / 100.0);
        result.put("maxPositiveGain", maxPnl);
        result.put("maxNegative", minPnl);
        result.put("avgGainPerTrade", avgGainPerTrade);
        result.put("avgLossPerTrade", avgLossPerTrade);
        result.put("totalAvg", totalAvg);
        result.put("avgCapPerGains", avgCapPerGains);
        result.put("avgHoldingDays", avgHoldingDays);
        return result;
    }

    private TransactionDTO toDTO(Transaction tx) {
        return TransactionDTO.builder()
                .id(tx.getId())
                .ticker(tx.getTicker())
                .type(tx.getType().name())
                .quantity(tx.getQuantity())
                .price(tx.getPrice())
                .transactionDate(tx.getTransactionDate())
                .createdAt(tx.getCreatedAt())
                .realizedPnl(tx.getRealizedPnl())
                .avgBuyPriceAtSell(tx.getAvgBuyPriceAtSell())
                .notes(tx.getNotes())
                .positionId(tx.getPositionId())
                .totalValue(tx.getPrice().multiply(BigDecimal.valueOf(tx.getQuantity())))
                .build();
    }
}
