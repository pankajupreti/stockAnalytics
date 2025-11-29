package com.example.reporting.service;

import com.example.reporting.model.StockAnalytics;
import com.example.reporting.repository.StockAnalyticsRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class StockService {

    private final StockAnalyticsRepository repository;

    public StockService(StockAnalyticsRepository repository) {
        this.repository = repository;
    }

    public List<StockAnalytics> getFilteredStocks(
            Double minMarketCap,
            Double minDailyChange,
            Double minRank1Week,
            Double minRank1Month,
            String sortBy,
            String order,
            String search,
            String view,
            int page,
            int pageSize // 👈 renamed for clarity
    ) {
        return repository.findAll().stream()
                .filter(s -> s.getCmp() != null && s.getMarketCap() != null)

                // ✅ View-based filters
                .filter(s -> {
                    return switch (view) {
                        case "52w" -> s.getCmp365() != null && s.getCmp() >= s.getCmp365();
                        case "recent" -> s.getCmp365() == null;
                        case "daily" -> s.getDailyChange() != null && s.getDailyChange() >= 10.0;
                        default -> true;
                    };
                })

                // ✅ Normal filters
                .filter(s -> minMarketCap == null || s.getMarketCap() >= minMarketCap)
                .filter(s -> minDailyChange == null || (s.getDailyChange() != null && s.getDailyChange() >= minDailyChange))
                .filter(s -> minRank1Week == null || (s.getRank1Week() != null && s.getRank1Week() >= minRank1Week))
                .filter(s -> minRank1Month == null || (s.getRank1Month() != null && s.getRank1Month() >= minRank1Month))
                .filter(s -> {
                    if (search == null || search.isBlank()) return true;
                    String q = search.toLowerCase();
                    return (s.getTicker() != null && s.getTicker().toLowerCase().contains(q)) ||
                            (s.getName() != null && s.getName().toLowerCase().contains(q));
                })

                // ✅ Sorting
                .sorted(getComparator(sortBy, order))

                // ✅ Pagination
                .skip((long) page * pageSize) // cast to long to avoid overflow
                .limit(pageSize)
                .collect(Collectors.toList());
    }


    private Comparator<StockAnalytics> getComparator(String sortBy, String order) {
        Comparator<StockAnalytics> comparator;

        switch (sortBy) {
            case "cmp" -> comparator = Comparator.comparing(
                    StockAnalytics::getCmp, Comparator.nullsLast(Double::compareTo));
            case "marketCap" -> comparator = Comparator.comparing(
                    StockAnalytics::getMarketCap, Comparator.nullsLast(Double::compareTo));
            case "dailyChange" -> comparator = Comparator.comparing(
                    StockAnalytics::getDailyChange, Comparator.nullsLast(Double::compareTo));
            case "rank1Week" -> comparator = Comparator.comparing(
                    StockAnalytics::getRank1Week, Comparator.nullsLast(Double::compareTo));
            case "rank1Month" -> comparator = Comparator.comparing(
                    StockAnalytics::getRank1Month, Comparator.nullsLast(Double::compareTo));
            case "rank1Year" -> comparator = Comparator.comparing(
                    StockAnalytics::getRank1Year, Comparator.nullsLast(Double::compareTo));
            case "rank2Month" -> comparator = Comparator.comparing(
                    StockAnalytics::getRank2Month, Comparator.nullsLast(Double::compareTo));
            case "name" -> comparator = Comparator.comparing(
                    StockAnalytics::getName, Comparator.nullsLast(String::compareToIgnoreCase));
            case "ticker" -> comparator = Comparator.comparing(
                    StockAnalytics::getTicker, Comparator.nullsLast(String::compareToIgnoreCase));
            case "lastUpdated" -> comparator = Comparator.comparing(
                    StockAnalytics::getLastUpdated, Comparator.nullsLast(LocalDateTime::compareTo));
            default -> comparator = Comparator.comparing(
                    StockAnalytics::getDailyChange, Comparator.nullsLast(Double::compareTo));
        }

        // ✅ Reverse order if "desc"
        if ("desc".equalsIgnoreCase(order)) {
            comparator = comparator.reversed();
        }

        return comparator;
    }



    public List<StockAnalytics> get52WeekHighs() {
        return repository.findAll().stream()
                .filter(s -> s.getCmp() != null && s.getCmp365() != null)
                .filter(s -> s.getCmp() >= s.getCmp365())
                .sorted(Comparator.comparing(StockAnalytics::getCmp).reversed())
                .collect(Collectors.toList());
    }
    public List<StockAnalytics> getTopDailyMovers() {
        return repository.findAll().stream()
                .filter(s -> s.getDailyChange() != null && s.getDailyChange() >= 5.0)
                .sorted(Comparator.comparing(StockAnalytics::getDailyChange).reversed())
                .collect(Collectors.toList());
    }

    public List<StockAnalytics> getRecentIpoStocks() {
        return repository.findAll().stream()
                .filter(s -> s.getCmp365() == null) // assume IPO if no 1Y price history
                .filter(s -> s.getCmp() != null)    // skip blank stocks
                .sorted(Comparator.comparing(StockAnalytics::getCmp).reversed())
                .collect(Collectors.toList());
    }

    private Double getFieldAsDouble(StockAnalytics s, String fieldName) {
        return switch (fieldName) {
            case "cmp" -> s.getCmp();
            case "marketCap" -> s.getMarketCap();
            case "dailyChange" -> s.getDailyChange();
            case "rank1Week" -> s.getRank1Week();
            case "rank1Month" -> s.getRank1Month();
            case "rank1Year" -> s.getRank1Year();
            default -> null;
        };
    }
}
