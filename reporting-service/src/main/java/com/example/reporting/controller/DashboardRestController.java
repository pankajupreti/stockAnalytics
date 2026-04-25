package com.example.reporting.controller;

import com.example.reporting.model.MarketBreadthResponse;
import com.example.reporting.model.StockAnalytics;
import com.example.reporting.service.AnchorMoveService;
import com.example.reporting.service.MarketBreadthService;
import com.example.reporting.service.StockService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
//@CrossOrigin(origins = {"http://localhost:8080","https://stockanalytics-hv70.onrender.com",}, allowCredentials = "true")
public class DashboardRestController {

    private final StockService stockService;
    private final MarketBreadthService marketBreadthService;
    private final AnchorMoveService anchorMoveService;

    public DashboardRestController(StockService stockService, MarketBreadthService marketBreadthService,
                                   AnchorMoveService anchorMoveService) {
        this.stockService = stockService;
        this.marketBreadthService = marketBreadthService;
        this.anchorMoveService = anchorMoveService;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> getDashboardData(
            @RequestParam(required = false) Double minMarketCap,
            @RequestParam(required = false) Double minDailyChange,
            @RequestParam(required = false) Double minRank1Week,
            @RequestParam(required = false) Double minRank1Month,
            @RequestParam(required = false) Double maxPctFrom52WHigh, // New: filter stocks within X% of 52W high
            @RequestParam(required = false) Double maxPctFrom52WLow,  // New: filter stocks within X% of 52W low
            @RequestParam(defaultValue = "rank1Week") String sortBy,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "all") String view,
            @RequestParam(defaultValue = "50") Integer pageSize,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchorDate
    ) {

        // If sorting by anchorMove, use special anchor-aware query
        if ("anchorMove".equals(sortBy) && anchorDate != null) {
            return getDashboardWithAnchorSort(minMarketCap, minDailyChange, minRank1Week, minRank1Month,
                    maxPctFrom52WHigh, maxPctFrom52WLow, order, page, search, view, pageSize, anchorDate);
        }

        List<StockAnalytics> stocks = stockService.getFilteredStocksWithPctFrom52W(
                minMarketCap, minDailyChange, minRank1Week, minRank1Month,
                maxPctFrom52WHigh, maxPctFrom52WLow,
                sortBy, order, search, view, page, pageSize);

        Map<String, Object> response = new HashMap<>();
        response.put("stocks", stocks);
        response.put("minMarketCap", minMarketCap);
        response.put("minDailyChange", minDailyChange);
        response.put("minRank1Week", minRank1Week);
        response.put("minRank1Month", minRank1Month);
        response.put("maxPctFrom52WHigh", maxPctFrom52WHigh);
        response.put("maxPctFrom52WLow", maxPctFrom52WLow);
        response.put("sortBy", sortBy);
        response.put("order", order);
        response.put("search", search);
        response.put("page", page);
        response.put("pageSize", pageSize);
        response.put("view", view);

        return response;
    }

    /**
     * Special handling when sorting by anchor move.
     * Gets ALL filtered stocks, calculates anchor move for each, sorts, then paginates.
     */
    private Map<String, Object> getDashboardWithAnchorSort(
            Double minMarketCap, Double minDailyChange, Double minRank1Week, Double minRank1Month,
            Double maxPctFrom52WHigh, Double maxPctFrom52WLow,
            String order, Integer page, String search, String view, Integer pageSize, LocalDate anchorDate) {

        // Get ALL filtered stocks (no pagination yet)
        List<StockAnalytics> allStocks = stockService.getFilteredStocksWithPctFrom52W(
                minMarketCap, minDailyChange, minRank1Week, minRank1Month,
                maxPctFrom52WHigh, maxPctFrom52WLow,
                "marketCap", "desc", search, view, 0, 10000); // Get all

        // Get anchor prices for the date
        Map<String, Double> anchorPrices = anchorMoveService.getPricesForDate(anchorDate);

        // Calculate anchor move for each stock and create DTOs
        List<Map<String, Object>> stocksWithAnchor = allStocks.stream()
                .map(stock -> {
                    Map<String, Object> dto = new HashMap<>();
                    dto.put("ticker", stock.getTicker());
                    dto.put("name", stock.getName());
                    dto.put("cmp", stock.getCmp());
                    dto.put("dailyChange", stock.getDailyChange());
                    dto.put("weekChange", stock.getRank1Week());
                    dto.put("monthChange", stock.getRank1Month());
                    dto.put("high52Week", stock.getHigh52Week());
                    dto.put("low52Week", stock.getLow52Week());
                    dto.put("marketCap", stock.getMarketCap());
                    dto.put("rsRating", stock.getRsRating());

                    // Calculate anchor move
                    String ticker = stock.getTicker() != null ?
                            stock.getTicker().toUpperCase().replace("NSE:", "").trim() : "";
                    Double anchorPrice = anchorPrices.get(ticker);

                    if (anchorPrice != null && anchorPrice > 0 && stock.getCmp() != null) {
                        double anchorMove = ((stock.getCmp() - anchorPrice) / anchorPrice) * 100;
                        dto.put("anchorPrice", anchorPrice);
                        dto.put("anchorMove", Math.round(anchorMove * 100.0) / 100.0);
                    } else {
                        dto.put("anchorPrice", null);
                        dto.put("anchorMove", null);
                    }

                    return dto;
                })
                .toList();

        // Sort by anchor move (nulls last)
        List<Map<String, Object>> sorted;
        if ("desc".equals(order)) {
            sorted = stocksWithAnchor.stream()
                    .sorted((a, b) -> {
                        Double aMove = (Double) a.get("anchorMove");
                        Double bMove = (Double) b.get("anchorMove");
                        if (aMove == null && bMove == null) return 0;
                        if (aMove == null) return 1;  // nulls last
                        if (bMove == null) return -1;
                        return Double.compare(bMove, aMove);
                    })
                    .toList();
        } else {
            sorted = stocksWithAnchor.stream()
                    .sorted((a, b) -> {
                        Double aMove = (Double) a.get("anchorMove");
                        Double bMove = (Double) b.get("anchorMove");
                        if (aMove == null && bMove == null) return 0;
                        if (aMove == null) return 1;  // nulls last
                        if (bMove == null) return -1;
                        return Double.compare(aMove, bMove);
                    })
                    .toList();
        }

        // Paginate
        int from = page * pageSize;
        int to = Math.min(from + pageSize, sorted.size());
        List<Map<String, Object>> pagedStocks = from < sorted.size() ? sorted.subList(from, to) : List.of();

        Map<String, Object> response = new HashMap<>();
        response.put("stocks", pagedStocks);
        response.put("minMarketCap", minMarketCap);
        response.put("minDailyChange", minDailyChange);
        response.put("minRank1Week", minRank1Week);
        response.put("minRank1Month", minRank1Month);
        response.put("maxPctFrom52WHigh", maxPctFrom52WHigh);
        response.put("maxPctFrom52WLow", maxPctFrom52WLow);
        response.put("sortBy", "anchorMove");
        response.put("order", order);
        response.put("search", search);
        response.put("page", page);
        response.put("pageSize", pageSize);
        response.put("view", view);
        response.put("anchorDate", anchorDate.toString());
        response.put("totalCount", sorted.size());

        return response;
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

    /**
     * Drill-down endpoint: Get all stocks in a specific sector/industry.
     * GET /api/market-breadth/sector?name=Technology&minMarketCap=500&sortBy=dailyChange&order=desc
     */
    @GetMapping("/market-breadth/sector")
    public Map<String, Object> getStocksBySector(
            @RequestParam String name,
            @RequestParam(required = false) Double minMarketCap,
            @RequestParam(defaultValue = "dailyChange") String sortBy,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "50") Integer pageSize) {

        List<StockAnalytics> allStocks = marketBreadthService.getStocksBySector(name, minMarketCap, sortBy, order);

        // Pagination
        int from = page * pageSize;
        int to = Math.min(from + pageSize, allStocks.size());
        List<StockAnalytics> pagedStocks = from < allStocks.size() ? allStocks.subList(from, to) : List.of();

        // Convert to DTOs with relevant fields
        List<Map<String, Object>> stockDtos = pagedStocks.stream()
                .map(s -> {
                    Map<String, Object> dto = new HashMap<>();
                    dto.put("ticker", s.getTicker());
                    dto.put("name", s.getName());
                    dto.put("cmp", s.getCmp());
                    dto.put("dailyChange", s.getDailyChange());
                    dto.put("marketCap", s.getMarketCap());
                    dto.put("high52Week", s.getHigh52Week());
                    dto.put("low52Week", s.getLow52Week());
                    dto.put("industry", s.getIndustry());
                    dto.put("sector", s.getSector());
                    return dto;
                })
                .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("sector", name);
        response.put("stocks", stockDtos);
        response.put("totalCount", allStocks.size());
        response.put("page", page);
        response.put("pageSize", pageSize);
        response.put("sortBy", sortBy);
        response.put("order", order);

        return response;
    }

    /**
     * Search stocks by ticker or company name for autocomplete.
     * GET /api/stocks/search?q=tejas
     * Returns list of matching stocks with ticker and name.
     */
    @GetMapping("/stocks/search")
    public List<Map<String, String>> searchStocks(@RequestParam("q") String query) {
        if (query == null || query.trim().length() < 2) {
            return List.of();
        }

        List<StockAnalytics> results = stockService.searchStocks(query.trim());

        return results.stream()
                .limit(15)
                .map(stock -> {
                    Map<String, String> item = new HashMap<>();
                    item.put("ticker", stock.getTicker());
                    item.put("name", stock.getName() != null ? stock.getName() : stock.getTicker());
                    // Create display label
                    String label = stock.getName() != null ? stock.getName() : stock.getTicker();
                    if (stock.getTicker() != null && !stock.getTicker().equals(stock.getName())) {
                        label += " (" + stock.getTicker().replace("NSE:", "") + ")";
                    }
                    item.put("label", label);
                    return item;
                })
                .toList();
    }

    /**
     * 52-Week Breakouts API - returns stocks near 52W high or low
     * GET /api/52w-breakouts?filter=high&threshold=5&page=0&search=
     */
    @GetMapping("/52w-breakouts")
    public Map<String, Object> get52WeekBreakouts(
            @RequestParam(defaultValue = "high") String filter, // high, low, all
            @RequestParam(defaultValue = "5") Double threshold, // % from 52W high/low
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "50") Integer pageSize,
            @RequestParam(required = false) String search
    ) {
        List<StockAnalytics> allStocks = stockService.getAllStocksWithValid52WData();

        // Calculate % from high and % from low, create DTOs
        List<Map<String, Object>> stockDtos = allStocks.stream()
                .map(s -> {
                    double pctFromHigh = ((s.getHigh52Week() - s.getCmp()) / s.getHigh52Week()) * 100;
                    double pctFromLow = ((s.getCmp() - s.getLow52Week()) / s.getLow52Week()) * 100;

                    Map<String, Object> dto = new HashMap<>();
                    dto.put("ticker", s.getTicker());
                    dto.put("name", s.getName());
                    dto.put("cmp", s.getCmp());
                    dto.put("high52Week", s.getHigh52Week());
                    dto.put("low52Week", s.getLow52Week());
                    dto.put("pctFromHigh", Math.round(pctFromHigh * 100.0) / 100.0);
                    dto.put("pctFromLow", Math.round(pctFromLow * 100.0) / 100.0);
                    dto.put("dailyChange", s.getDailyChange());
                    dto.put("marketCap", s.getMarketCap());
                    return dto;
                })
                .toList();

        // Apply filter
        List<Map<String, Object>> filtered;
        if ("high".equals(filter)) {
            filtered = stockDtos.stream()
                    .filter(dto -> (Double) dto.get("pctFromHigh") <= threshold)
                    .sorted((a, b) -> Double.compare((Double) a.get("pctFromHigh"), (Double) b.get("pctFromHigh")))
                    .toList();
        } else if ("low".equals(filter)) {
            filtered = stockDtos.stream()
                    .filter(dto -> (Double) dto.get("pctFromLow") <= threshold)
                    .sorted((a, b) -> Double.compare((Double) a.get("pctFromLow"), (Double) b.get("pctFromLow")))
                    .toList();
        } else {
            filtered = stockDtos.stream()
                    .sorted((a, b) -> Double.compare((Double) a.get("pctFromHigh"), (Double) b.get("pctFromHigh")))
                    .toList();
        }

        // Apply search
        if (search != null && !search.trim().isEmpty()) {
            String searchLower = search.toLowerCase();
            filtered = filtered.stream()
                    .filter(dto -> {
                        String ticker = (String) dto.get("ticker");
                        String name = (String) dto.get("name");
                        return (ticker != null && ticker.toLowerCase().contains(searchLower))
                                || (name != null && name.toLowerCase().contains(searchLower));
                    })
                    .toList();
        }

        // Pagination
        int from = page * pageSize;
        int to = Math.min(from + pageSize, filtered.size());
        List<Map<String, Object>> pagedStocks = from < filtered.size() ? filtered.subList(from, to) : List.of();

        Map<String, Object> response = new HashMap<>();
        response.put("stocks", pagedStocks);
        response.put("filter", filter);
        response.put("threshold", threshold);
        response.put("page", page);
        response.put("pageSize", pageSize);
        response.put("search", search);
        response.put("totalCount", filtered.size());

        return response;
    }
}


