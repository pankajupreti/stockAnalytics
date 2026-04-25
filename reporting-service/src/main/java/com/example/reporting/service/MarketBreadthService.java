package com.example.reporting.service;

import com.example.reporting.model.MarketBreadthResponse;
import com.example.reporting.model.MarketBreadthResponse.SectorStats;
import com.example.reporting.model.MarketBreadthResponse.TopStock;
import com.example.reporting.model.StockAnalytics;
import com.example.reporting.repository.StockAnalyticsRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MarketBreadthService {

    private final StockAnalyticsRepository stockRepo;

    public MarketBreadthService(StockAnalyticsRepository stockRepo) {
        this.stockRepo = stockRepo;
    }

    public MarketBreadthResponse compute(Double minMarketCap, double[] thresholds) {

        // 1) Load universe (DB-side filter if possible)
        List<StockAnalytics> universe = (minMarketCap == null)
                ? stockRepo.findAll()
                : stockRepo.findByMarketCapGreaterThanEqual(minMarketCap);

        MarketBreadthResponse resp = new MarketBreadthResponse();
        resp.total = universe.size();

        // 2) Pull null-safe daily_change as Double list
        List<Double> changes = universe.stream()
                .map(s -> s.getDailyChange() != null ? s.getDailyChange() : 0.0)
                .collect(Collectors.toList());

        // 3) Headline breadth
        resp.green = (int) changes.stream().filter(x -> x >= 0.0).count();
        resp.red = resp.total - resp.green;
        resp.greenPct = resp.total == 0 ? 0 : round2((resp.green * 100.0) / resp.total);
        resp.greenRedRatio = round2(resp.red == 0 ? resp.green : (resp.green * 1.0 / resp.red));

        // 4) Threshold counts
        double t1 = thresholds.length > 0 ? thresholds[0] : 3.0;
        double t2 = thresholds.length > 1 ? thresholds[1] : 5.0;
        double t3 = thresholds.length > 2 ? thresholds[2] : 8.0;

        resp.above3 = (int) changes.stream().filter(x -> x >= t1).count();
        resp.above5 = (int) changes.stream().filter(x -> x >= t2).count();
        resp.above8 = (int) changes.stream().filter(x -> x >= t3).count();
        resp.below3 = (int) changes.stream().filter(x -> x <= -t1).count();
        resp.below5 = (int) changes.stream().filter(x -> x <= -t2).count();
        resp.below8 = (int) changes.stream().filter(x -> x <= -t3).count();

        // 5) Compute sector/industry breakdown
        resp.sectorLeaders = computeSectorStats(universe);

        return resp;
    }

    /**
     * Compute sector-level statistics grouped by industry field.
     * Returns list sorted by average daily change (descending).
     */
    private List<SectorStats> computeSectorStats(List<StockAnalytics> universe) {
        // Group stocks by industry (use "Other" if null)
        Map<String, List<StockAnalytics>> byIndustry = universe.stream()
                .collect(Collectors.groupingBy(s ->
                    s.getIndustry() != null && !s.getIndustry().isBlank()
                        ? s.getIndustry()
                        : (s.getSector() != null && !s.getSector().isBlank() ? s.getSector() : "Other")
                ));

        List<SectorStats> sectorStatsList = new ArrayList<>();

        for (Map.Entry<String, List<StockAnalytics>> entry : byIndustry.entrySet()) {
            String sector = entry.getKey();
            List<StockAnalytics> stocks = entry.getValue();

            if (stocks.isEmpty()) continue;

            int stockCount = stocks.size();
            int greenCount = (int) stocks.stream()
                    .filter(s -> s.getDailyChange() != null && s.getDailyChange() >= 0)
                    .count();
            int redCount = stockCount - greenCount;

            double avgDailyChange = stocks.stream()
                    .mapToDouble(s -> s.getDailyChange() != null ? s.getDailyChange() : 0.0)
                    .average()
                    .orElse(0.0);

            double greenPct = stockCount == 0 ? 0 : round2((greenCount * 100.0) / stockCount);

            // Top 3 gainers in this sector
            List<TopStock> topGainers = stocks.stream()
                    .filter(s -> s.getDailyChange() != null)
                    .sorted((a, b) -> Double.compare(b.getDailyChange(), a.getDailyChange()))
                    .limit(3)
                    .map(s -> new TopStock(
                            s.getTicker(),
                            s.getName(),
                            round2(s.getDailyChange()),
                            s.getCmp(),
                            s.getMarketCap()
                    ))
                    .collect(Collectors.toList());

            // Top 3 losers in this sector
            List<TopStock> topLosers = stocks.stream()
                    .filter(s -> s.getDailyChange() != null && s.getDailyChange() < 0)
                    .sorted(Comparator.comparingDouble(StockAnalytics::getDailyChange))
                    .limit(3)
                    .map(s -> new TopStock(
                            s.getTicker(),
                            s.getName(),
                            round2(s.getDailyChange()),
                            s.getCmp(),
                            s.getMarketCap()
                    ))
                    .collect(Collectors.toList());

            sectorStatsList.add(new SectorStats(
                    sector, stockCount, greenCount, redCount,
                    round2(avgDailyChange), greenPct,
                    topGainers, topLosers
            ));
        }

        // Sort by average daily change descending (best performing sectors first)
        sectorStatsList.sort((a, b) -> Double.compare(b.avgDailyChange, a.avgDailyChange));

        return sectorStatsList;
    }

    /**
     * Get all stocks in a specific sector/industry with sorting.
     */
    public List<StockAnalytics> getStocksBySector(String sector, Double minMarketCap, String sortBy, String order) {
        List<StockAnalytics> universe = (minMarketCap == null)
                ? stockRepo.findAll()
                : stockRepo.findByMarketCapGreaterThanEqual(minMarketCap);

        // Filter by sector or industry
        List<StockAnalytics> filtered = universe.stream()
                .filter(s -> {
                    String stockSector = s.getIndustry() != null && !s.getIndustry().isBlank()
                            ? s.getIndustry()
                            : (s.getSector() != null && !s.getSector().isBlank() ? s.getSector() : "Other");
                    return stockSector.equalsIgnoreCase(sector);
                })
                .collect(Collectors.toList());

        // Sort
        Comparator<StockAnalytics> comparator = getComparator(sortBy);
        if ("desc".equalsIgnoreCase(order)) {
            comparator = comparator.reversed();
        }
        filtered.sort(comparator);

        return filtered;
    }

    private Comparator<StockAnalytics> getComparator(String sortBy) {
        return switch (sortBy != null ? sortBy.toLowerCase() : "dailychange") {
            case "marketcap" -> Comparator.comparingDouble(s -> s.getMarketCap() != null ? s.getMarketCap() : 0);
            case "cmp" -> Comparator.comparingDouble(s -> s.getCmp() != null ? s.getCmp() : 0);
            case "name" -> Comparator.comparing(s -> s.getName() != null ? s.getName() : "", String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparingDouble(s -> s.getDailyChange() != null ? s.getDailyChange() : 0);
        };
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}


