package com.example.portfolio_service.service;

import com.example.portfolio_service.dto.QuoteDTO;
import com.example.portfolio_service.model.PortfolioSnapshot;
import com.example.portfolio_service.model.Position;
import com.example.portfolio_service.model.Transaction;
import com.example.portfolio_service.model.Transaction.TransactionType;
import com.example.portfolio_service.repository.PortfolioSnapshotRepository;
import com.example.portfolio_service.repository.PositionRepository;
import com.example.portfolio_service.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for calculating portfolio returns using XIRR methodology.
 *
 * XIRR (Extended Internal Rate of Return) calculates the annualized return
 * considering the timing and amount of all cash flows.
 *
 * Cash flows:
 * - Negative: Money going OUT (BUY transactions)
 * - Positive: Money coming IN (SELL transactions + current portfolio value)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioReturnsService {

    private final TransactionRepository transactionRepository;
    private final PositionRepository positionRepository;
    private final PortfolioSnapshotRepository snapshotRepository;
    private final WebClient reportingWebClient;

    /**
     * Calculate XIRR for entire portfolio
     */
    public PortfolioReturnsDTO calculatePortfolioXIRR(String userSub, String jwtToken) {
        // Get all transactions ordered by date
        List<Transaction> transactions = transactionRepository
                .findByUserSubOrderByTransactionDateAscCreatedAtAsc(userSub);

        if (transactions.isEmpty()) {
            return PortfolioReturnsDTO.empty();
        }

        // Get current portfolio positions and their market values
        List<Position> positions = positionRepository.findByUserSubOrderByIdAsc(userSub);
        BigDecimal currentPortfolioValue = calculateCurrentPortfolioValue(positions, jwtToken);

        // Build cash flows for XIRR
        List<XIRRCalculator.CashFlow> cashFlows = new ArrayList<>();

        // Add all transactions as cash flows
        for (Transaction tx : transactions) {
            double amount;
            if (tx.getType() == TransactionType.BUY) {
                // BUY = money out = negative
                amount = -tx.getPrice().multiply(BigDecimal.valueOf(tx.getQuantity())).doubleValue();
            } else {
                // SELL = money in = positive
                amount = tx.getPrice().multiply(BigDecimal.valueOf(tx.getQuantity())).doubleValue();
            }
            cashFlows.add(new XIRRCalculator.CashFlow(tx.getTransactionDate(), amount));
        }

        // Add current portfolio value as final cash flow (money that could come in today)
        if (currentPortfolioValue.compareTo(BigDecimal.ZERO) > 0) {
            cashFlows.add(new XIRRCalculator.CashFlow(LocalDate.now(), currentPortfolioValue.doubleValue()));
        }

        // Calculate XIRR
        Double xirr = XIRRCalculator.safeCalculateXIRR(cashFlows);

        // Calculate additional metrics
        BigDecimal totalInvested = calculateTotalInvested(transactions);
        BigDecimal totalRealized = calculateTotalRealized(transactions);
        BigDecimal unrealizedPnl = calculateUnrealizedPnl(positions, jwtToken);
        BigDecimal totalPnl = totalRealized.add(unrealizedPnl);

        // Calculate holding period
        LocalDate firstTransaction = transactions.get(0).getTransactionDate();
        long daysSinceFirst = java.time.temporal.ChronoUnit.DAYS.between(firstTransaction, LocalDate.now());

        // Simple return = (Current Value + Realized) / Total Invested - 1
        BigDecimal simpleReturn = BigDecimal.ZERO;
        if (totalInvested.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal totalValue = currentPortfolioValue.add(totalRealized);
            simpleReturn = totalValue.subtract(totalInvested)
                    .divide(totalInvested, 6, RoundingMode.HALF_UP);
        }

        return PortfolioReturnsDTO.builder()
                .xirr(xirr)
                .xirrPercentage(xirr != null ? xirr * 100 : null)
                .simpleReturn(simpleReturn.doubleValue())
                .simpleReturnPercentage(simpleReturn.multiply(BigDecimal.valueOf(100)).doubleValue())
                .totalInvested(totalInvested)
                .currentPortfolioValue(currentPortfolioValue)
                .totalRealizedPnl(totalRealized)
                .unrealizedPnl(unrealizedPnl)
                .totalPnl(totalPnl)
                .daysSinceFirstInvestment(daysSinceFirst)
                .firstInvestmentDate(firstTransaction)
                .totalTransactions(transactions.size())
                .activePositions(positions.size())
                .build();
    }

    /**
     * Calculate current portfolio value using live market prices
     */
    private BigDecimal calculateCurrentPortfolioValue(List<Position> positions, String jwtToken) {
        if (positions.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalValue = BigDecimal.ZERO;

        // Collect all tickers
        List<String> tickers = positions.stream()
                .map(Position::getTicker)
                .collect(Collectors.toList());

        // Fetch current prices
        Map<String, BigDecimal> prices = fetchCurrentPrices(tickers, jwtToken);

        for (Position pos : positions) {
            String ticker = pos.getTicker();
            BigDecimal currentPrice = prices.getOrDefault(ticker, pos.getBuyPrice()); // fallback to buy price
            BigDecimal posValue = currentPrice.multiply(BigDecimal.valueOf(pos.getQuantity()));
            totalValue = totalValue.add(posValue);
        }

        return totalValue;
    }

    /**
     * Fetch current prices for tickers from quotes service.
     * Uses internal endpoint (no auth) for scheduled jobs, or authenticated endpoint for user requests.
     */
    private Map<String, BigDecimal> fetchCurrentPrices(List<String> tickers, String jwtToken) {
        Map<String, BigDecimal> prices = new HashMap<>();

        if (tickers.isEmpty()) {
            return prices;
        }

        try {
            // Build tickers param
            String tickersParam = String.join(",", tickers.stream()
                    .map(t -> t.toUpperCase(Locale.ROOT))
                    .sorted()
                    .collect(Collectors.toList()));

            // Use internal endpoint if no JWT token (for scheduled jobs)
            String endpoint = (jwtToken == null || jwtToken.isEmpty())
                    ? "/public/internal/quotes"
                    : "/api/quotes";

            var request = reportingWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(endpoint)
                            .queryParam("tickers", tickersParam)
                            .build());

            // Add JWT token if provided (for authenticated calls)
            if (jwtToken != null && !jwtToken.isEmpty()) {
                request = request.header("Authorization", "Bearer " + jwtToken);
            }

            List<QuoteDTO> quotes = request
                    .retrieve()
                    .bodyToFlux(QuoteDTO.class)
                    .collectList()
                    .blockOptional()
                    .orElse(List.of());

            // Map ticker -> price
            for (QuoteDTO quote : quotes) {
                if (quote.getTicker() != null && quote.getPrice() != null) {
                    prices.put(quote.getTicker().toUpperCase(Locale.ROOT), quote.getPrice());
                }
            }

            log.debug("Fetched {} prices using {} endpoint", prices.size(), endpoint);

        } catch (Exception e) {
            log.warn("Failed to fetch quotes for XIRR calculation: {}", e.getMessage());
        }

        return prices;
    }

    /**
     * Calculate total amount invested (sum of all BUY transactions)
     */
    private BigDecimal calculateTotalInvested(List<Transaction> transactions) {
        return transactions.stream()
                .filter(tx -> tx.getType() == TransactionType.BUY)
                .map(tx -> tx.getPrice().multiply(BigDecimal.valueOf(tx.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Calculate total realized P&L (from SELL transactions)
     */
    private BigDecimal calculateTotalRealized(List<Transaction> transactions) {
        return transactions.stream()
                .filter(tx -> tx.getType() == TransactionType.SELL && tx.getRealizedPnl() != null)
                .map(Transaction::getRealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Calculate unrealized P&L for current positions
     */
    private BigDecimal calculateUnrealizedPnl(List<Position> positions, String jwtToken) {
        if (positions.isEmpty()) {
            return BigDecimal.ZERO;
        }

        List<String> tickers = positions.stream()
                .map(Position::getTicker)
                .collect(Collectors.toList());

        Map<String, BigDecimal> prices = fetchCurrentPrices(tickers, jwtToken);

        BigDecimal unrealizedPnl = BigDecimal.ZERO;
        for (Position pos : positions) {
            BigDecimal currentPrice = prices.getOrDefault(pos.getTicker(), pos.getBuyPrice());
            BigDecimal currentValue = currentPrice.multiply(BigDecimal.valueOf(pos.getQuantity()));
            BigDecimal costBasis = pos.getBuyPrice().multiply(BigDecimal.valueOf(pos.getQuantity()));
            unrealizedPnl = unrealizedPnl.add(currentValue.subtract(costBasis));
        }

        return unrealizedPnl;
    }

    // ==================== PORTFOLIO SNAPSHOTS ====================

    /**
     * Capture today's portfolio snapshot.
     * If snapshot already exists for today, it will be UPDATED with current prices.
     * This ensures the 4pm scheduled job updates morning snapshots with closing prices.
     */
    public PortfolioSnapshot captureSnapshot(String userSub, String jwtToken) {
        LocalDate today = LocalDate.now();

        // Check if snapshot already exists for today - we'll update it instead of skipping
        Optional<PortfolioSnapshot> existingOpt = snapshotRepository.findByUserSubAndSnapshotDate(userSub, today);

        // Get current positions and calculate values
        List<Position> positions = positionRepository.findByUserSubOrderByIdAsc(userSub);
        if (positions.isEmpty()) {
            log.info("No positions to snapshot for user {}", userSub);
            return null;
        }

        BigDecimal totalValue = calculateCurrentPortfolioValue(positions, jwtToken);
        BigDecimal totalInvested = positions.stream()
                .map(p -> p.getBuyPrice().multiply(BigDecimal.valueOf(p.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unrealizedPnl = totalValue.subtract(totalInvested);

        log.info("Portfolio values: totalValue(market)={}, totalInvested(cost)={}, unrealizedPnl={}",
                totalValue, totalInvested, unrealizedPnl);

        // Get cumulative realized P&L from all sells up to today
        BigDecimal realizedPnl = transactionRepository.getRealizedPnlAsOfDate(userSub, today);
        if (realizedPnl == null) {
            realizedPnl = BigDecimal.ZERO;
        }

        // Get sell proceeds up to today
        // For new snapshots, we include all sales up to today because:
        // - Positions are already updated when a sale happens (real-time)
        // - So sellProceeds should also include those sales
        // Note: The morning snapshot logic (using yesterday) is only for MIGRATION
        // of historical snapshots where we don't know the exact sale time
        BigDecimal sellProceeds = transactionRepository.getSellProceedsAsOfDate(userSub, today);
        if (sellProceeds == null) {
            sellProceeds = BigDecimal.ZERO;
        }

        // Total wealth = portfolio value + sell proceeds (actual cash from sales)
        // This correctly accounts for sold positions:
        // - When you sell ₹1L position for ₹1.2L, portfolioValue drops by market value
        // - But sellProceeds increases by ₹1.2L (actual cash received)
        // - Net effect: totalWealth reflects true financial position
        BigDecimal totalWealth = totalValue.add(sellProceeds);

        // Calculate normalized value based on ADJUSTED total wealth
        // The key insight: normalized value should track PERFORMANCE, not capital additions
        // Formula: normalizedValue = (totalWealth / adjustedBase) * 100
        // Where adjustedBase = firstTotalWealth + (currentTotalInvested - firstTotalInvested)
        // This way, when you add new capital, the base grows proportionally
        BigDecimal normalizedValue = BigDecimal.valueOf(100);
        Optional<PortfolioSnapshot> firstSnapshot = snapshotRepository.findFirstByUserSubOrderBySnapshotDateAsc(userSub);
        if (firstSnapshot.isPresent()) {
            BigDecimal firstWealth = firstSnapshot.get().getTotalWealth() != null
                    ? firstSnapshot.get().getTotalWealth()
                    : firstSnapshot.get().getTotalValue();

            // Get total invested from TRANSACTIONS using CREATED_AT date (when stock was added to system).
            // This ensures adding stocks retroactively (with old buy dates) doesn't spike today's snapshot.
            LocalDate firstDate = firstSnapshot.get().getSnapshotDate();
            BigDecimal firstTotalBuys = transactionRepository.getTotalInvestedByCreatedDate(userSub, firstDate);
            BigDecimal currentTotalBuys = transactionRepository.getTotalInvestedByCreatedDate(userSub, today);

            if (firstTotalBuys == null || firstTotalBuys.compareTo(BigDecimal.ZERO) == 0) {
                firstTotalBuys = firstWealth;
            }
            if (currentTotalBuys == null) {
                currentTotalBuys = firstTotalBuys;
            }

            // Capital added = total buys today - total buys at first snapshot
            // This represents NEW money invested since the first snapshot
            BigDecimal capitalAdded = currentTotalBuys.subtract(firstTotalBuys);
            if (capitalAdded.compareTo(BigDecimal.ZERO) < 0) {
                capitalAdded = BigDecimal.ZERO;
            }
            BigDecimal adjustedBase = firstWealth.add(capitalAdded);

            log.info("Snapshot calc: firstWealth={}, firstTotalBuys={}, currentTotalBuys={}, capitalAdded={}, adjustedBase={}, totalWealth={}",
                    firstWealth, firstTotalBuys, currentTotalBuys, capitalAdded, adjustedBase, totalWealth);

            if (adjustedBase.compareTo(BigDecimal.ZERO) > 0) {
                normalizedValue = totalWealth.divide(adjustedBase, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }
        }

        PortfolioSnapshot snapshot;
        if (existingOpt.isPresent()) {
            // Update existing snapshot with current values
            snapshot = existingOpt.get();
            snapshot.setTotalValue(totalValue);
            snapshot.setTotalInvested(totalInvested);
            snapshot.setUnrealizedPnl(unrealizedPnl);
            snapshot.setRealizedPnl(realizedPnl);
            snapshot.setTotalWealth(totalWealth);
            snapshot.setPositionsCount(positions.size());
            snapshot.setNormalizedValue(normalizedValue);
            log.info("Updating existing snapshot for user {} on {}: value={}, wealth={}, normalized={}",
                    userSub, today, totalValue, totalWealth, normalizedValue);
        } else {
            // Create new snapshot
            snapshot = PortfolioSnapshot.builder()
                    .userSub(userSub)
                    .snapshotDate(today)
                    .totalValue(totalValue)
                    .totalInvested(totalInvested)
                    .unrealizedPnl(unrealizedPnl)
                    .realizedPnl(realizedPnl)
                    .totalWealth(totalWealth)
                    .positionsCount(positions.size())
                    .normalizedValue(normalizedValue)
                    .build();
            log.info("Creating new snapshot for user {} on {}: value={}, wealth={}, normalized={}",
                    userSub, today, totalValue, totalWealth, normalizedValue);
        }

        PortfolioSnapshot saved = snapshotRepository.save(snapshot);
        return saved;
    }

    /**
     * Get historical snapshots for a user.
     * Recalculates normalized values on-the-fly using transaction history
     * to ensure correctness even if stored values are stale.
     */
    public PortfolioHistoryDTO getPortfolioHistory(String userSub) {
        List<PortfolioSnapshot> snapshots = snapshotRepository.findByUserSubOrderBySnapshotDateAsc(userSub);

        if (snapshots.isEmpty()) {
            return PortfolioHistoryDTO.empty();
        }

        PortfolioSnapshot first = snapshots.get(0);
        BigDecimal firstWealth = first.getTotalWealth() != null ? first.getTotalWealth() : first.getTotalValue();

        // Load all BUY transactions once and build cumulative invested map keyed by CREATED_AT date.
        // We use createdAt (when the stock was actually added to the system) instead of transactionDate
        // (the historical buy date) so that adding stocks retroactively doesn't spike past snapshots.
        List<Transaction> buyTxns = transactionRepository.findByUserSubAndTypeOrderByTransactionDateAsc(
                userSub, Transaction.TransactionType.BUY);
        java.util.TreeMap<LocalDate, BigDecimal> cumulativeBuys = new java.util.TreeMap<>();
        BigDecimal runningTotal = BigDecimal.ZERO;
        // Sort by createdAt for cumulative tracking
        buyTxns.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));
        for (Transaction tx : buyTxns) {
            runningTotal = runningTotal.add(tx.getPrice().multiply(BigDecimal.valueOf(tx.getQuantity())));
            cumulativeBuys.put(tx.getCreatedAt().toLocalDate(), runningTotal);
        }

        // Helper: get total buys as-of a date using the TreeMap (O(log n) instead of DB query)
        LocalDate firstDate = first.getSnapshotDate();
        java.util.Map.Entry<LocalDate, BigDecimal> firstEntry = cumulativeBuys.floorEntry(firstDate);
        BigDecimal firstTotalBuys = firstEntry != null ? firstEntry.getValue() : BigDecimal.ZERO;
        if (firstTotalBuys.compareTo(BigDecimal.ZERO) == 0) {
            firstTotalBuys = firstWealth;
        }

        List<String> dates = new ArrayList<>();
        List<Double> normalizedValues = new ArrayList<>();
        List<Double> rawValues = new ArrayList<>();
        List<Double> wealthValues = new ArrayList<>();
        List<Double> investedValues = new ArrayList<>();
        List<Double> rawInvestedValues = new ArrayList<>();
        final BigDecimal baseBuys = firstTotalBuys;

        for (PortfolioSnapshot s : snapshots) {
            dates.add(s.getSnapshotDate().toString());
            rawValues.add(s.getTotalValue().doubleValue());
            double wealth = s.getTotalWealth() != null ? s.getTotalWealth().doubleValue() : s.getTotalValue().doubleValue();
            wealthValues.add(wealth);

            // Recalculate normalized value from totalWealth and transaction history
            BigDecimal snapshotWealth = s.getTotalWealth() != null ? s.getTotalWealth() : s.getTotalValue();
            java.util.Map.Entry<LocalDate, BigDecimal> entry = cumulativeBuys.floorEntry(s.getSnapshotDate());
            BigDecimal buysAsOfDate = entry != null ? entry.getValue() : baseBuys;

            BigDecimal capitalAdded = buysAsOfDate.subtract(baseBuys);
            if (capitalAdded.compareTo(BigDecimal.ZERO) < 0) {
                capitalAdded = BigDecimal.ZERO;
            }
            BigDecimal adjustedBase = firstWealth.add(capitalAdded);

            double normalized = 100.0;
            if (adjustedBase.compareTo(BigDecimal.ZERO) > 0) {
                normalized = snapshotWealth.divide(adjustedBase, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).doubleValue();
            }
            normalizedValues.add(Math.round(normalized * 100.0) / 100.0);

            // Normalized invested capital (base=100, same scale as portfolio performance)
            double normalizedInvested = 100.0;
            if (baseBuys.compareTo(BigDecimal.ZERO) > 0) {
                normalizedInvested = buysAsOfDate.divide(baseBuys, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).doubleValue();
            }
            investedValues.add(Math.round(normalizedInvested * 100.0) / 100.0);

            // Raw invested amount in rupees
            double rawInvested = s.getTotalInvested() != null ? s.getTotalInvested().doubleValue() : 0.0;
            rawInvestedValues.add(Math.round(rawInvested * 100.0) / 100.0);
        }

        double lastNormalized = normalizedValues.get(normalizedValues.size() - 1);
        double returnPct = lastNormalized - 100.0;

        log.info("Portfolio history: {} snapshots, lastNormalized={}, returnPct={}%",
                snapshots.size(), lastNormalized, returnPct);

        return PortfolioHistoryDTO.builder()
                .dates(dates)
                .normalizedValues(normalizedValues)
                .rawValues(wealthValues)
                .investedValues(investedValues)
                .rawInvestedValues(rawInvestedValues)
                .dataPoints(snapshots.size())
                .firstDate(first.getSnapshotDate())
                .lastDate(snapshots.get(snapshots.size() - 1).getSnapshotDate())
                .returnPercentage(Math.round(returnPct * 100.0) / 100.0)
                .success(true)
                .build();
    }

    /**
     * Capture snapshots for all users who have positions.
     * Called by scheduler daily.
     * Note: This uses buy prices as fallback since we don't have JWT tokens for scheduled jobs.
     */
    public int captureSnapshotsForAllUsers() {
        // Get all distinct userSubs who have positions
        List<String> userSubs = positionRepository.findDistinctUserSubs();
        log.info("Starting daily snapshot capture for {} users", userSubs.size());

        int successCount = 0;
        LocalDate today = LocalDate.now();

        for (String userSub : userSubs) {
            try {
                // Check if snapshot already exists - we'll update it instead of skipping
                Optional<PortfolioSnapshot> existingOpt = snapshotRepository.findByUserSubAndSnapshotDate(userSub, today);

                // Get positions
                List<Position> positions = positionRepository.findByUserSubOrderByIdAsc(userSub);
                if (positions.isEmpty()) continue;

                // Calculate values using buy prices as fallback (no live quotes in scheduled job)
                BigDecimal totalValue = BigDecimal.ZERO;
                BigDecimal totalInvested = BigDecimal.ZERO;

                for (Position pos : positions) {
                    // Use buy price as estimate (live prices not available without user token)
                    BigDecimal posValue = pos.getBuyPrice().multiply(BigDecimal.valueOf(pos.getQuantity()));
                    totalValue = totalValue.add(posValue);
                    totalInvested = totalInvested.add(posValue);
                }

                // Try to get live prices if possible (using internal API)
                try {
                    List<String> tickers = positions.stream()
                            .map(Position::getTicker)
                            .collect(Collectors.toList());
                    Map<String, BigDecimal> prices = fetchCurrentPrices(tickers, null);
                    if (!prices.isEmpty()) {
                        totalValue = BigDecimal.ZERO;
                        for (Position pos : positions) {
                            BigDecimal price = prices.getOrDefault(pos.getTicker(), pos.getBuyPrice());
                            totalValue = totalValue.add(price.multiply(BigDecimal.valueOf(pos.getQuantity())));
                        }
                    }
                } catch (Exception e) {
                    // Use buy price fallback
                }

                BigDecimal unrealizedPnl = totalValue.subtract(totalInvested);

                // Get cumulative realized P&L from all sells up to today
                BigDecimal realizedPnl = transactionRepository.getRealizedPnlAsOfDate(userSub, today);
                if (realizedPnl == null) {
                    realizedPnl = BigDecimal.ZERO;
                }

                // Get total sell proceeds up to today (for consistency)
                BigDecimal sellProceeds = transactionRepository.getSellProceedsAsOfDate(userSub, today);
                if (sellProceeds == null) {
                    sellProceeds = BigDecimal.ZERO;
                }

                // Total wealth = portfolio value + sell proceeds (actual cash from sales)
                // This correctly accounts for sold positions
                BigDecimal totalWealth = totalValue.add(sellProceeds);

                // Calculate normalized value based on ADJUSTED total wealth
                // Adjust for capital additions so new investments don't spike the graph
                BigDecimal normalizedValue = BigDecimal.valueOf(100);
                Optional<PortfolioSnapshot> firstSnapshot = snapshotRepository.findFirstByUserSubOrderBySnapshotDateAsc(userSub);
                if (firstSnapshot.isPresent()) {
                    BigDecimal firstWealth = firstSnapshot.get().getTotalWealth() != null
                            ? firstSnapshot.get().getTotalWealth()
                            : firstSnapshot.get().getTotalValue();

                    // Use TRANSACTIONS with CREATED_AT date for capital tracking (same as captureSnapshot).
                    // Using createdAt ensures retroactive stock additions don't spike past snapshots.
                    LocalDate firstDate = firstSnapshot.get().getSnapshotDate();
                    BigDecimal firstTotalBuys = transactionRepository.getTotalInvestedByCreatedDate(userSub, firstDate);
                    BigDecimal currentTotalBuys = transactionRepository.getTotalInvestedByCreatedDate(userSub, today);

                    if (firstTotalBuys == null || firstTotalBuys.compareTo(BigDecimal.ZERO) == 0) {
                        firstTotalBuys = firstWealth;
                    }
                    if (currentTotalBuys == null) {
                        currentTotalBuys = firstTotalBuys;
                    }

                    BigDecimal capitalAdded = currentTotalBuys.subtract(firstTotalBuys);
                    if (capitalAdded.compareTo(BigDecimal.ZERO) < 0) {
                        capitalAdded = BigDecimal.ZERO;
                    }
                    BigDecimal adjustedBase = firstWealth.add(capitalAdded);

                    if (adjustedBase.compareTo(BigDecimal.valueOf(1)) < 0) {
                        adjustedBase = firstWealth;
                    }

                    if (adjustedBase.compareTo(BigDecimal.ZERO) > 0) {
                        normalizedValue = totalWealth.divide(adjustedBase, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100));
                    }
                }

                PortfolioSnapshot snapshot;
                if (existingOpt.isPresent()) {
                    // Update existing snapshot with current values (e.g., morning snapshot updated at 4pm)
                    snapshot = existingOpt.get();
                    snapshot.setTotalValue(totalValue);
                    snapshot.setTotalInvested(totalInvested);
                    snapshot.setUnrealizedPnl(unrealizedPnl);
                    snapshot.setRealizedPnl(realizedPnl);
                    snapshot.setTotalWealth(totalWealth);
                    snapshot.setPositionsCount(positions.size());
                    snapshot.setNormalizedValue(normalizedValue);
                    log.debug("Updating existing snapshot for user {} on {}", userSub, today);
                } else {
                    // Create new snapshot
                    snapshot = PortfolioSnapshot.builder()
                            .userSub(userSub)
                            .snapshotDate(today)
                            .totalValue(totalValue)
                            .totalInvested(totalInvested)
                            .unrealizedPnl(unrealizedPnl)
                            .realizedPnl(realizedPnl)
                            .totalWealth(totalWealth)
                            .positionsCount(positions.size())
                            .normalizedValue(normalizedValue)
                            .build();
                    log.debug("Creating new snapshot for user {} on {}", userSub, today);
                }

                snapshotRepository.save(snapshot);
                successCount++;

            } catch (Exception e) {
                log.error("Error capturing snapshot for user {}: {}", userSub, e.getMessage());
            }
        }

        log.info("Daily snapshot capture completed. Captured {} snapshots", successCount);
        return successCount;
    }

    // ==================== SNAPSHOT MIGRATION ====================

    /**
     * Recalculate totalWealth and normalizedValue for all historical snapshots.
     * This fixes snapshots that were created with the incorrect formula
     * (using realizedPnl instead of sellProceeds).
     *
     * @return number of snapshots fixed
     */
    public int recalculateHistoricalSnapshots() {
        log.info("Starting historical snapshot recalculation...");

        // Get all snapshots ordered by user and date
        List<PortfolioSnapshot> allSnapshots = snapshotRepository.findAll();
        log.info("Found {} total snapshots to process", allSnapshots.size());

        // Group by user
        Map<String, List<PortfolioSnapshot>> byUser = allSnapshots.stream()
                .collect(Collectors.groupingBy(PortfolioSnapshot::getUserSub));

        int fixedCount = 0;

        for (Map.Entry<String, List<PortfolioSnapshot>> entry : byUser.entrySet()) {
            String userSub = entry.getKey();
            List<PortfolioSnapshot> userSnapshots = entry.getValue();

            // Sort by date
            userSnapshots.sort(Comparator.comparing(PortfolioSnapshot::getSnapshotDate));

            if (userSnapshots.isEmpty()) continue;

            // Get the base values from the first snapshot (for normalized calculation)
            PortfolioSnapshot firstSnapshot = userSnapshots.get(0);
            BigDecimal firstWealth = null;
            BigDecimal firstInvested = null;

            for (PortfolioSnapshot snapshot : userSnapshots) {
                try {
                    LocalDate snapshotDate = snapshot.getSnapshotDate();

                    // Get sell proceeds as of the snapshot date
                    BigDecimal sellProceeds = transactionRepository.getSellProceedsAsOfDate(userSub, snapshotDate);
                    if (sellProceeds == null) {
                        sellProceeds = BigDecimal.ZERO;
                    }

                    // Also update realizedPnl to be accurate as of this date
                    BigDecimal realizedPnl = transactionRepository.getRealizedPnlAsOfDate(userSub, snapshotDate);
                    if (realizedPnl == null) {
                        realizedPnl = BigDecimal.ZERO;
                    }

                    // Get total invested as of this snapshot date FROM TRANSACTIONS
                    // This is crucial - we must recalculate from transactions, not use stored snapshot values
                    BigDecimal totalInvested = transactionRepository.getTotalInvestedAsOfDate(userSub, snapshotDate);
                    if (totalInvested == null) {
                        totalInvested = BigDecimal.ZERO;
                    }

                    // Update the snapshot's totalInvested to be accurate
                    snapshot.setTotalInvested(totalInvested);

                    // Calculate correct totalWealth = portfolioValue + sellProceeds
                    BigDecimal portfolioValue = snapshot.getTotalValue();
                    BigDecimal newTotalWealth = portfolioValue.add(sellProceeds);

                    // Set base values from first snapshot
                    if (firstWealth == null) {
                        firstWealth = newTotalWealth;
                        firstInvested = totalInvested;
                    }

                    // Calculate normalized value with capital adjustment
                    // This prevents graph from spiking when new stocks are added
                    // Formula: normalized = (currentWealth / (firstWealth + capitalAdded)) * 100
                    BigDecimal normalizedValue = BigDecimal.valueOf(100);
                    if (firstWealth.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal capitalAdded = totalInvested.subtract(firstInvested);
                        if (capitalAdded.compareTo(BigDecimal.ZERO) < 0) {
                            capitalAdded = BigDecimal.ZERO;
                        }
                        BigDecimal adjustedBase = firstWealth.add(capitalAdded);
                        if (adjustedBase.compareTo(BigDecimal.ZERO) > 0) {
                            normalizedValue = newTotalWealth.divide(adjustedBase, 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100));
                        }
                    }

                    // Update snapshot
                    snapshot.setRealizedPnl(realizedPnl);
                    snapshot.setTotalWealth(newTotalWealth);
                    snapshot.setNormalizedValue(normalizedValue);

                    snapshotRepository.save(snapshot);
                    fixedCount++;

                    log.debug("Fixed snapshot for user {} on {}: wealth={}, invested={}, normalized={}",
                            userSub, snapshotDate, newTotalWealth, totalInvested, normalizedValue);

                } catch (Exception e) {
                    log.error("Error fixing snapshot {} for user {}: {}",
                            snapshot.getId(), userSub, e.getMessage());
                }
            }
        }

        log.info("Historical snapshot recalculation completed. Fixed {} snapshots", fixedCount);
        return fixedCount;
    }

    /**
     * DTO for portfolio history
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PortfolioHistoryDTO {
        private List<String> dates;
        private List<Double> normalizedValues;
        private List<Double> rawValues;
        private List<Double> investedValues;  // normalized capital invested (base=100)
        private List<Double> rawInvestedValues;  // actual invested amount in rupees
        private Integer dataPoints;
        private LocalDate firstDate;
        private LocalDate lastDate;
        private Double returnPercentage;
        private Boolean success;

        public static PortfolioHistoryDTO empty() {
            return PortfolioHistoryDTO.builder()
                    .dates(List.of())
                    .normalizedValues(List.of())
                    .rawValues(List.of())
                    .investedValues(List.of())
                    .rawInvestedValues(List.of())
                    .dataPoints(0)
                    .firstDate(null)
                    .lastDate(null)
                    .returnPercentage(0.0)
                    .success(false)
                    .build();
        }
    }

    /**
     * DTO for portfolio returns
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PortfolioReturnsDTO {
        private Double xirr;                      // XIRR as decimal (e.g., 0.15 for 15%)
        private Double xirrPercentage;            // XIRR as percentage (e.g., 15.0 for 15%)
        private Double simpleReturn;              // Simple return as decimal
        private Double simpleReturnPercentage;    // Simple return as percentage
        private BigDecimal totalInvested;         // Total amount invested (all BUYs)
        private BigDecimal currentPortfolioValue; // Current market value
        private BigDecimal totalRealizedPnl;      // Realized P&L from sells
        private BigDecimal unrealizedPnl;         // Unrealized P&L from current positions
        private BigDecimal totalPnl;              // Total P&L (realized + unrealized)
        private Long daysSinceFirstInvestment;    // Days since first investment
        private LocalDate firstInvestmentDate;    // Date of first investment
        private Integer totalTransactions;        // Total number of transactions
        private Integer activePositions;          // Number of current positions

        public static PortfolioReturnsDTO empty() {
            return PortfolioReturnsDTO.builder()
                    .xirr(null)
                    .xirrPercentage(null)
                    .simpleReturn(0.0)
                    .simpleReturnPercentage(0.0)
                    .totalInvested(BigDecimal.ZERO)
                    .currentPortfolioValue(BigDecimal.ZERO)
                    .totalRealizedPnl(BigDecimal.ZERO)
                    .unrealizedPnl(BigDecimal.ZERO)
                    .totalPnl(BigDecimal.ZERO)
                    .daysSinceFirstInvestment(0L)
                    .firstInvestmentDate(null)
                    .totalTransactions(0)
                    .activePositions(0)
                    .build();
        }
    }
}
