package com.example.reporting.service;

import com.example.reporting.model.StockAnalytics;
import com.example.reporting.repository.StockAnalyticsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Service for calculating RS (Relative Strength) Rating.
 *
 * RS Rating is a percentile rank (1-99) that compares a stock's price performance
 * against all other stocks in the database.
 *
 * Formula (IBD-inspired, adapted to available data):
 * Composite Score = (40% × 2-month performance) + (20% × 1-month performance) + (40% × 1-year performance)
 *
 * Then all stocks are ranked by composite score and assigned a percentile (1-99).
 * RS Rating of 99 means the stock outperformed 99% of all other stocks.
 * RS Rating of 50 means average performance.
 */
@Service
public class RsRatingService {

    private static final Logger log = LoggerFactory.getLogger(RsRatingService.class);

    private final StockAnalyticsRepository repository;

    public RsRatingService(StockAnalyticsRepository repository) {
        this.repository = repository;
    }

    /**
     * Calculate and update RS Rating for all stocks.
     * Runs daily at 6:30 PM IST (after market close) and 8:00 AM IST (before market open).
     */
    @Scheduled(cron = "0 30 18 * * MON-FRI", zone = "Asia/Kolkata")  // 6:30 PM IST on weekdays
    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Kolkata")   // 8:00 AM IST on weekdays
    public void scheduledRsRatingUpdate() {
        log.info("Starting scheduled RS Rating calculation");
        int updated = calculateAndUpdateRsRatings();
        log.info("RS Rating calculation complete. Updated {} stocks", updated);
    }

    /**
     * Calculate RS Rating for all stocks and update the database.
     *
     * @return Number of stocks updated
     */
    @Transactional
    public int calculateAndUpdateRsRatings() {
        List<StockAnalytics> allStocks = repository.findAll();

        if (allStocks.isEmpty()) {
            log.warn("No stocks found for RS Rating calculation");
            return 0;
        }

        // Step 1: Calculate composite score for each stock
        List<StockWithScore> scoredStocks = new ArrayList<>();

        for (StockAnalytics stock : allStocks) {
            Double compositeScore = calculateCompositeScore(stock);
            if (compositeScore != null) {
                scoredStocks.add(new StockWithScore(stock, compositeScore));
            }
        }

        if (scoredStocks.isEmpty()) {
            log.warn("No stocks with valid performance data for RS Rating calculation");
            return 0;
        }

        // Step 2: Sort by composite score (ascending - lower score = worse performance)
        scoredStocks.sort(Comparator.comparingDouble(StockWithScore::score));

        // Step 3: Assign percentile rank (1-99)
        int totalStocks = scoredStocks.size();
        LocalDateTime now = LocalDateTime.now();
        int updatedCount = 0;

        for (int i = 0; i < totalStocks; i++) {
            StockWithScore scored = scoredStocks.get(i);

            // Percentile = (rank / total) * 100, clamped to 1-99
            // rank is 1-based, so i+1
            double percentile = ((double) (i + 1) / totalStocks) * 100;
            int rsRating = (int) Math.round(percentile);

            // Clamp to 1-99 range
            rsRating = Math.max(1, Math.min(99, rsRating));

            StockAnalytics stock = scored.stock();
            stock.setRsRating((double) rsRating);
            stock.setRsRatingUpdated(now);
            repository.save(stock);
            updatedCount++;
        }

        log.info("Calculated RS Rating for {} stocks (out of {} total)", updatedCount, allStocks.size());
        return updatedCount;
    }

    /**
     * Calculate composite performance score for a stock.
     *
     * Formula: 40% × 2-month + 20% × 1-month + 40% × 1-year
     * Fallbacks: Use rank1Week if rank1Month missing, calculate 1-year from cmp/cmp365
     *
     * Returns null if no performance data available.
     */
    private Double calculateCompositeScore(StockAnalytics stock) {
        Double rank2Month = stock.getRank2Month();
        Double rank1Month = stock.getRank1Month();
        Double rank1Year = stock.getRank1Year();
        Double rank1Week = stock.getRank1Week();

        // Fallback: Use rank1Week if rank1Month is not available
        if (rank1Month == null && rank1Week != null) {
            rank1Month = rank1Week;
        }

        // Fallback: Calculate 1-year performance from cmp and cmp365
        if (rank1Year == null && stock.getCmp() != null && stock.getCmp365() != null && stock.getCmp365() > 0) {
            rank1Year = ((stock.getCmp() - stock.getCmp365()) / stock.getCmp365()) * 100;
        }

        // Count valid metrics - need at least 1 to calculate
        int validMetrics = 0;
        if (rank2Month != null) validMetrics++;
        if (rank1Month != null) validMetrics++;
        if (rank1Year != null) validMetrics++;

        if (validMetrics < 1) {
            return null;
        }

        // Calculate weighted score, adjusting weights if some metrics are missing
        double totalWeight = 0;
        double weightedSum = 0;

        if (rank2Month != null) {
            weightedSum += 0.40 * rank2Month;
            totalWeight += 0.40;
        }
        if (rank1Month != null) {
            weightedSum += 0.20 * rank1Month;
            totalWeight += 0.20;
        }
        if (rank1Year != null) {
            weightedSum += 0.40 * rank1Year;
            totalWeight += 0.40;
        }

        // Normalize by actual weight used
        return weightedSum / totalWeight;
    }

    /**
     * Get RS Rating for a specific ticker.
     * If not calculated yet, returns null.
     */
    public Double getRsRating(String ticker) {
        return repository.findById(ticker)
                .map(StockAnalytics::getRsRating)
                .orElse(null);
    }

    /**
     * Force recalculation of RS Rating for a specific ticker.
     * Useful for newly added stocks.
     */
    @Transactional
    public Double recalculateForTicker(String ticker) {
        // For a single stock, we need the full ranking context
        // So we recalculate all ratings
        calculateAndUpdateRsRatings();
        return getRsRating(ticker);
    }

    // Helper record to hold stock with its composite score
    private record StockWithScore(StockAnalytics stock, Double score) {}
}
