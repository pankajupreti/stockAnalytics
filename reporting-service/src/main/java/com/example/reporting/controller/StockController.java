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
}