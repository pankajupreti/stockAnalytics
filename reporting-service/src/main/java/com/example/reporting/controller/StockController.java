// StockController.java with pagination, filters, and sorting for top gainers
package com.example.reporting.controller;

import com.example.reporting.model.StockAnalytics;
import com.example.reporting.repository.StockAnalyticsRepository;
import com.example.reporting.service.StockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Controller
public class StockController {

    private final StockAnalyticsRepository repository;
    @Autowired
    private StockService stockService;

    public StockController(StockAnalyticsRepository repository) {
        this.repository = repository;
    }

    @CrossOrigin(origins = "http://localhost:8080", allowCredentials = "true")
    @GetMapping("/dashboard")
    public String showDashboard(
            @RequestParam(required = false) Double minMarketCap,
            @RequestParam(required = false) Double minDailyChange,
            @RequestParam(required = false) Double minRank1Week,
            @RequestParam(required = false) Double minRank1Month,
            @RequestParam(defaultValue = "rank1Week") String sortBy,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "all") String view,
            Model model
    ) {
        int pageSize = 50;


        List<StockAnalytics> stocks = stockService.getFilteredStocks(
                minMarketCap, minDailyChange, minRank1Week, minRank1Month,
                sortBy, order, search, view, page, pageSize);


        model.addAttribute("stocks", stocks);
        model.addAttribute("minMarketCap", minMarketCap);
        model.addAttribute("minDailyChange", minDailyChange);
        model.addAttribute("minRank1Week", minRank1Week);
        model.addAttribute("minRank1Month", minRank1Month);
        model.addAttribute("sortBy", sortBy);
        model.addAttribute("order", order);
        model.addAttribute("search", search);
        model.addAttribute("page", page);
        model.addAttribute("view", view);


        return "dashboard";
    }





    @GetMapping("/top-gainers")
    public List<StockAnalytics> getTopGainers(
            @RequestParam(required = false) Double minMarketCap,
            @RequestParam(required = false) Double minRank1Week,
            @RequestParam(required = false) Double minDailyChange,
            @RequestParam(required = false) Double minRank1Month,
            @RequestParam(defaultValue = "rank1Week") String sortBy,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        List<StockAnalytics> filtered = repository.findAll().stream()
                .filter(s -> s.getCmp() != null && s.getMarketCap() != null)
                .filter(s -> minMarketCap == null || s.getMarketCap() >= minMarketCap)
                .filter(s -> minRank1Week == null || (s.getRank1Week() != null && s.getRank1Week() >= minRank1Week))
                .filter(s -> minDailyChange == null || (s.getDailyChange() != null && s.getDailyChange() >= minDailyChange))
                .filter(s -> minRank1Month == null || (s.getRank1Month() != null && s.getRank1Month() >= minRank1Month))
                .sorted((a, b) -> compareByField(b, a, sortBy))
                .limit(500)
                .collect(Collectors.toList());

        int from = page * size;
        int to = Math.min(from + size, filtered.size());
        if (from > to) return List.of();

        return filtered.subList(from, to);
    }

    private int compareByField(StockAnalytics a, StockAnalytics b, String field) {
        try {
            Double valA = getFieldAsDouble(a, field);
            Double valB = getFieldAsDouble(b, field);
            return Double.compare(valA != null ? valA : 0, valB != null ? valB : 0);
        } catch (Exception e) {
            return 0;
        }
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

    /**
     * 52-Week Breakouts page - shows stocks near 52W high or low
     */
    @CrossOrigin(origins = "http://localhost:8080", allowCredentials = "true")
    @GetMapping("/52w-breakouts")
    public String show52WeekBreakouts(
            @RequestParam(defaultValue = "high") String filter, // high, low, all
            @RequestParam(defaultValue = "5") Double threshold, // % from 52W high/low
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(required = false) String search,
            Model model
    ) {
        int pageSize = 50;

        List<StockAnalytics> allStocks = repository.findAll().stream()
                .filter(s -> s.getCmp() != null && s.getHigh52Week() != null && s.getLow52Week() != null)
                .filter(s -> s.getHigh52Week() > 0 && s.getLow52Week() > 0)
                .collect(Collectors.toList());

        // Calculate % from high and % from low for each stock
        List<Stock52WDto> stockDtos = allStocks.stream()
                .map(s -> {
                    double pctFromHigh = ((s.getHigh52Week() - s.getCmp()) / s.getHigh52Week()) * 100;
                    double pctFromLow = ((s.getCmp() - s.getLow52Week()) / s.getLow52Week()) * 100;
                    return new Stock52WDto(s, pctFromHigh, pctFromLow);
                })
                .collect(Collectors.toList());

        // Apply filter
        if ("high".equals(filter)) {
            // Near 52W high: within threshold% of high
            stockDtos = stockDtos.stream()
                    .filter(dto -> dto.pctFromHigh <= threshold)
                    .sorted(Comparator.comparingDouble(dto -> dto.pctFromHigh))
                    .collect(Collectors.toList());
        } else if ("low".equals(filter)) {
            // Near 52W low: within threshold% of low
            stockDtos = stockDtos.stream()
                    .filter(dto -> dto.pctFromLow <= threshold)
                    .sorted(Comparator.comparingDouble(dto -> dto.pctFromLow))
                    .collect(Collectors.toList());
        } else {
            // All - sort by nearest to high
            stockDtos = stockDtos.stream()
                    .sorted(Comparator.comparingDouble(dto -> dto.pctFromHigh))
                    .collect(Collectors.toList());
        }

        // Apply search
        if (search != null && !search.trim().isEmpty()) {
            String searchLower = search.toLowerCase();
            stockDtos = stockDtos.stream()
                    .filter(dto -> dto.stock.getTicker().toLowerCase().contains(searchLower)
                            || (dto.stock.getName() != null && dto.stock.getName().toLowerCase().contains(searchLower)))
                    .collect(Collectors.toList());
        }

        // Pagination
        int from = page * pageSize;
        int to = Math.min(from + pageSize, stockDtos.size());
        List<Stock52WDto> pagedStocks = from < stockDtos.size() ? stockDtos.subList(from, to) : List.of();

        model.addAttribute("stocks", pagedStocks);
        model.addAttribute("filter", filter);
        model.addAttribute("threshold", threshold);
        model.addAttribute("page", page);
        model.addAttribute("search", search);
        model.addAttribute("totalCount", stockDtos.size());

        return "52w-breakouts";
    }

    // DTO for 52W breakouts page
    public static class Stock52WDto {
        public StockAnalytics stock;
        public double pctFromHigh;
        public double pctFromLow;

        public Stock52WDto(StockAnalytics stock, double pctFromHigh, double pctFromLow) {
            this.stock = stock;
            this.pctFromHigh = pctFromHigh;
            this.pctFromLow = pctFromLow;
        }

        public StockAnalytics getStock() { return stock; }
        public double getPctFromHigh() { return pctFromHigh; }
        public double getPctFromLow() { return pctFromLow; }
    }
}