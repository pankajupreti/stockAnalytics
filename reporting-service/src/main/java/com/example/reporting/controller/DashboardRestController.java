package com.example.reporting.controller;

import com.example.reporting.model.MarketBreadthResponse;
import com.example.reporting.model.StockAnalytics;
import com.example.reporting.service.MarketBreadthService;
import com.example.reporting.service.StockService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
//@CrossOrigin(origins = {"http://localhost:8080","https://gateway-service-ux71.onrender.com",}, allowCredentials = "true")
public class DashboardRestController {

    private final StockService stockService;
    private final MarketBreadthService marketBreadthService;

    public DashboardRestController(StockService stockService, MarketBreadthService marketBreadthService) {
        this.stockService = stockService;
        this.marketBreadthService = marketBreadthService;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> getDashboardData(
            @RequestParam(required = false) Double minMarketCap,
            @RequestParam(required = false) Double minDailyChange,
            @RequestParam(required = false) Double minRank1Week,
            @RequestParam(required = false) Double minRank1Month,
            @RequestParam(defaultValue = "rank1Week") String sortBy,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "all") String view,
            @RequestParam(defaultValue = "50") Integer pageSize // 👈 new param
    ) {

        List<StockAnalytics> stocks = stockService.getFilteredStocks(
                minMarketCap, minDailyChange, minRank1Week, minRank1Month,
                sortBy, order, search, view, page, pageSize);

        Map<String, Object> response = new HashMap<>();
        response.put("stocks", stocks);
        response.put("minMarketCap", minMarketCap);
        response.put("minDailyChange", minDailyChange);
        response.put("minRank1Week", minRank1Week);
        response.put("minRank1Month", minRank1Month);
        response.put("sortBy", sortBy);
        response.put("order", order);
        response.put("search", search);
        response.put("page", page);
        response.put("pageSize", pageSize); // 👈 include in response
        response.put("view", view);

        return response; // Spring Boot auto-serializes to JSON
    }

    @GetMapping("/market-breadth")
    public MarketBreadthResponse marketBreadth(
            @RequestParam(required = false) Double minMarketCap,
            @RequestParam(defaultValue = "3") double t1,
            @RequestParam(defaultValue = "5") double t2,
            @RequestParam(defaultValue = "8") double t3,
            @RequestParam(defaultValue = "true") boolean includeSectors) {

        double[] thresholds = {t1, t2, t3};
        return marketBreadthService.compute(minMarketCap, thresholds);
    }
}


