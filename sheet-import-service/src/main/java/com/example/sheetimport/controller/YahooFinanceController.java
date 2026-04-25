package com.example.sheetimport.controller;

import com.example.sheetimport.model.StockAnalytics;
import com.example.sheetimport.repository.StockAnalyticsRepository;
import com.example.sheetimport.scheduler.YahooStockDiscoveryJob;
import com.example.sheetimport.scheduler.YahooPriceUpdateJob;
import com.example.sheetimport.service.GoogleSheetService;
import com.example.sheetimport.service.YahooFinanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for Yahoo Finance 52-week data operations.
 * Provides endpoints for manual sync and status checks.
 */
@RestController
@RequestMapping("/api/yahoo")
public class YahooFinanceController {

    private final YahooFinanceService yahooFinanceService;
    private final YahooStockDiscoveryJob discoveryJob;
    private final YahooPriceUpdateJob priceUpdateJob;
    private final StockAnalyticsRepository repository;
    private final GoogleSheetService googleSheetService;

    public YahooFinanceController(YahooFinanceService yahooFinanceService,
                                  YahooStockDiscoveryJob discoveryJob,
                                  YahooPriceUpdateJob priceUpdateJob,
                                  StockAnalyticsRepository repository,
                                  GoogleSheetService googleSheetService) {
        this.yahooFinanceService = yahooFinanceService;
        this.discoveryJob = discoveryJob;
        this.priceUpdateJob = priceUpdateJob;
        this.repository = repository;
        this.googleSheetService = googleSheetService;
    }

    // ==================== STOCK DISCOVERY ENDPOINTS ====================

    /**
     * Manually trigger stock discovery from Yahoo Finance.
     * POST /api/yahoo/discover
     * Finds new NSE stocks not in the database and adds them with source=YAHOO.
     */
    @PostMapping("/discover")
    public ResponseEntity<Map<String, Object>> discoverNewStocks() {
        int discovered = discoveryJob.triggerDiscovery();
        Map<String, Object> response = new HashMap<>();
        response.put("status", "completed");
        response.put("newStocksDiscovered", discovered);
        return ResponseEntity.ok(response);
    }

    /**
     * Manually trigger price update for YAHOO-sourced stocks.
     * POST /api/yahoo/update-prices
     */
    @PostMapping("/update-prices")
    public ResponseEntity<Map<String, Object>> updateYahooPrices() {
        int updated = priceUpdateJob.triggerPriceUpdate();
        Map<String, Object> response = new HashMap<>();
        response.put("status", "completed");
        response.put("updatedCount", updated);
        return ResponseEntity.ok(response);
    }

    /**
     * Refresh live prices from Yahoo Finance for specific tickers (any source).
     * POST /api/yahoo/refresh-prices?tickers=NSE:TCS,NSE:INFY,...
     */
    @PostMapping("/refresh-prices")
    public ResponseEntity<Map<String, Object>> refreshPricesForTickers(
            @RequestParam List<String> tickers) {
        int updated = priceUpdateJob.updatePricesForTickers(tickers);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "completed");
        response.put("updatedCount", updated);
        response.put("requestedCount", tickers.size());
        return ResponseEntity.ok(response);
    }

    /**
     * Get statistics about stock sources.
     * GET /api/yahoo/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        List<StockAnalytics> allStocks = repository.findAll();
        long yahooCount = allStocks.stream().filter(s -> "YAHOO".equals(s.getSource())).count();
        long sheetCount = allStocks.stream().filter(s -> "SHEET".equals(s.getSource())).count();
        long unknownCount = allStocks.stream().filter(s -> s.getSource() == null).count();

        Map<String, Object> response = new HashMap<>();
        response.put("totalStocks", allStocks.size());
        response.put("yahooSourced", yahooCount);
        response.put("sheetSourced", sheetCount);
        response.put("unknownSource", unknownCount);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all YAHOO-sourced stocks.
     * GET /api/yahoo/stocks
     */
    @GetMapping("/stocks")
    public ResponseEntity<List<StockAnalytics>> getYahooStocks() {
        List<StockAnalytics> stocks = repository.findBySource("YAHOO");
        return ResponseEntity.ok(stocks);
    }

    /**
     * Update industry/sector data for YAHOO-sourced stocks.
     * POST /api/yahoo/update-industry?limit=10
     * Fetches industry data from NSE API.
     * @param limit Max stocks to process (default 20, max 100)
     */
    @PostMapping("/update-industry")
    public ResponseEntity<Map<String, Object>> updateYahooIndustry(
            @RequestParam(defaultValue = "20") int limit) {

        List<StockAnalytics> yahooStocks = repository.findBySource("YAHOO");

        if (yahooStocks.isEmpty()) {
            Map<String, Object> emptyResponse = new HashMap<>();
            emptyResponse.put("status", "completed");
            emptyResponse.put("message", "No YAHOO-sourced stocks found");
            emptyResponse.put("updatedCount", 0);
            return ResponseEntity.ok(emptyResponse);
        }

        // Filter to stocks without industry data and apply limit
        int maxLimit = Math.min(limit, 100);
        List<StockAnalytics> toProcess = yahooStocks.stream()
                .filter(s -> s.getIndustry() == null || s.getIndustry().isEmpty())
                .limit(maxLimit)
                .collect(java.util.stream.Collectors.toList());

        int updated = 0;
        int failed = 0;
        for (StockAnalytics stock : toProcess) {
            if (updateStockIndustry(stock)) {
                updated++;
            } else {
                failed++;
            }
            // Small delay to avoid rate limiting
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "completed");
        response.put("updatedCount", updated);
        response.put("failedCount", failed);
        response.put("processedCount", toProcess.size());
        response.put("totalYahooStocks", yahooStocks.size());
        response.put("remainingWithoutIndustry", yahooStocks.stream()
                .filter(s -> s.getIndustry() == null || s.getIndustry().isEmpty()).count() - updated);
        return ResponseEntity.ok(response);
    }

    /**
     * Update industry for a single stock.
     */
    private boolean updateStockIndustry(StockAnalytics stock) {
        String ticker = stock.getTicker();
        String nseSymbol = ticker;
        if (nseSymbol.startsWith("NSE:")) {
            nseSymbol = nseSymbol.substring(4);
        }
        nseSymbol = nseSymbol.toUpperCase();

        try {
            // Try NSE API first
            String apiUrl = "https://www.nseindia.com/api/quote-equity?symbol=" + nseSymbol;

            java.net.URL url = new java.net.URL(apiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return false;
            }

            StringBuilder response = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(response.toString());

            boolean updated = false;

            // Get industry from info section
            if (root.has("info") && root.path("info").has("industry")) {
                String industry = root.path("info").get("industry").asText();
                if (industry != null && !industry.isEmpty()) {
                    stock.setIndustry(industry);
                    updated = true;
                }
            }

            // Get sector from metadata
            if (root.has("metadata") && root.path("metadata").has("pdSectorInd")) {
                String sectorIndex = root.path("metadata").get("pdSectorInd").asText();
                if (sectorIndex != null && !sectorIndex.isEmpty()) {
                    stock.setSector(mapSectorIndex(sectorIndex));
                    updated = true;
                }
            }

            if (updated) {
                stock.setSectorUpdated(java.time.LocalDateTime.now());
                repository.save(stock);
            }

            return updated;

        } catch (Exception e) {
            return false;
        }
    }

    private String mapSectorIndex(String sectorIndex) {
        if (sectorIndex == null) return null;
        String upper = sectorIndex.toUpperCase();

        if (upper.contains("IT") || upper.contains("DIGITAL")) return "Technology";
        if (upper.contains("BANK") || upper.contains("FINANCIAL")) return "Financial Services";
        if (upper.contains("PHARMA") || upper.contains("HEALTHCARE")) return "Healthcare";
        if (upper.contains("AUTO")) return "Automobile";
        if (upper.contains("METAL") || upper.contains("STEEL")) return "Metals & Mining";
        if (upper.contains("ENERGY") || upper.contains("OIL") || upper.contains("GAS")) return "Energy";
        if (upper.contains("FMCG") || upper.contains("CONSUMER")) return "Consumer Goods";
        if (upper.contains("REALTY") || upper.contains("INFRA")) return "Real Estate";
        if (upper.contains("TELECOM") || upper.contains("MEDIA")) return "Telecom & Media";

        return sectorIndex;
    }

    /**
     * Manually trigger 52-week high/low sync for all stocks.
     * POST /api/yahoo/sync-all
     */
    @PostMapping("/sync-all")
    public ResponseEntity<Map<String, Object>> syncAll() {
        int updated = yahooFinanceService.updateAll52WeekData();
        Map<String, Object> response = new HashMap<>();
        response.put("status", "completed");
        response.put("updatedCount", updated);
        return ResponseEntity.ok(response);
    }

    /**
     * Sync only stale stocks (not updated today).
     * POST /api/yahoo/sync-stale
     */
    @PostMapping("/sync-stale")
    public ResponseEntity<Map<String, Object>> syncStale() {
        int updated = yahooFinanceService.updateStale52WeekData();
        Map<String, Object> response = new HashMap<>();
        response.put("status", "completed");
        response.put("updatedCount", updated);
        return ResponseEntity.ok(response);
    }

    /**
     * Sync 52-week data for a single stock.
     * POST /api/yahoo/sync/{ticker}
     */
    @PostMapping("/sync/{ticker}")
    public ResponseEntity<Map<String, Object>> syncSingle(@PathVariable String ticker) {
        boolean success = yahooFinanceService.fetch52WeekData(ticker);
        Map<String, Object> response = new HashMap<>();
        response.put("ticker", ticker);
        response.put("success", success);
        return ResponseEntity.ok(response);
    }

    /**
     * Health check for Yahoo Finance integration.
     * GET /api/yahoo/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "ok");
        response.put("service", "yahoo-finance-52week");
        return ResponseEntity.ok(response);
    }

    /**
     * Update market cap for YAHOO-sourced stocks that are missing it.
     * POST /api/yahoo/update-marketcap?limit=50
     */
    @PostMapping("/update-marketcap")
    public ResponseEntity<Map<String, Object>> updateMarketCap(
            @RequestParam(defaultValue = "50") int limit) {

        List<StockAnalytics> yahooStocks = repository.findBySource("YAHOO");

        // Filter to stocks without market cap
        int maxLimit = Math.min(limit, 200);
        List<StockAnalytics> toProcess = yahooStocks.stream()
                .filter(s -> s.getMarketCap() == null)
                .limit(maxLimit)
                .collect(java.util.stream.Collectors.toList());

        int updated = 0;
        int failed = 0;

        int consecutiveFailures = 0;

        for (StockAnalytics stock : toProcess) {
            // Extract clean NSE symbol from ticker (remove NSE: prefix)
            String nseSymbol = stock.getTicker();
            if (nseSymbol == null) {
                failed++;
                continue;
            }
            if (nseSymbol.startsWith("NSE:")) {
                nseSymbol = nseSymbol.substring(4);
            }
            nseSymbol = nseSymbol.toUpperCase();

            // Use Yahoo v10 quoteSummary API with retry
            Double marketCap = fetchMarketCapFromYahooV10(nseSymbol);

            // Retry once if failed (crumb might have expired)
            if (marketCap == null) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                yahooCrumb = null; // Force crumb refresh
                marketCap = fetchMarketCapFromYahooV10(nseSymbol);
            }

            if (marketCap != null) {
                stock.setMarketCap(marketCap);
                stock.setLastUpdated(java.time.LocalDateTime.now());
                repository.save(stock);
                updated++;
                consecutiveFailures = 0;
            } else {
                failed++;
                consecutiveFailures++;

                // If too many consecutive failures, slow down significantly
                if (consecutiveFailures >= 5) {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                    yahooCrumb = null; // Force crumb refresh
                    consecutiveFailures = 0;
                }
            }

            // Rate limiting - 1.5 seconds between requests to avoid throttling
            try { Thread.sleep(1500); } catch (InterruptedException e) { break; }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "completed");
        response.put("updatedCount", updated);
        response.put("failedCount", failed);
        response.put("processedCount", toProcess.size());
        response.put("totalYahooStocks", yahooStocks.size());
        response.put("remainingWithoutMarketCap", yahooStocks.stream()
                .filter(s -> s.getMarketCap() == null).count() - updated);
        return ResponseEntity.ok(response);
    }

    /**
     * Test Yahoo v10 quoteSummary API for market cap.
     * GET /api/yahoo/test-marketcap/{ticker}
     * Example: /api/yahoo/test-marketcap/TCS
     */
    @GetMapping("/test-marketcap/{ticker}")
    public ResponseEntity<Map<String, Object>> testMarketCap(@PathVariable String ticker) {
        Map<String, Object> response = new HashMap<>();
        response.put("ticker", ticker);

        // Clean up symbol
        String nseSymbol = ticker.toUpperCase();
        if (nseSymbol.startsWith("NSE:")) {
            nseSymbol = nseSymbol.substring(4);
        }
        if (nseSymbol.endsWith(".NS")) {
            nseSymbol = nseSymbol.substring(0, nseSymbol.length() - 3);
        }
        String yahooSymbol = nseSymbol + ".NS";
        response.put("nseSymbol", nseSymbol);
        response.put("yahooSymbol", yahooSymbol);

        try {
            // First get crumb and cookies
            boolean crumbObtained = false;
            if (yahooCrumb == null || System.currentTimeMillis() > crumbExpiry) {
                crumbObtained = refreshYahooCrumb();
            } else {
                crumbObtained = true;
            }
            response.put("crumbObtained", crumbObtained);
            response.put("hasCookies", yahooCookies != null && !yahooCookies.isEmpty());

            if (!crumbObtained) {
                response.put("error", "Failed to obtain Yahoo crumb token");
                return ResponseEntity.ok(response);
            }

            // Use Yahoo v10 quoteSummary API with crumb
            String apiUrl = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/" + yahooSymbol
                    + "?modules=summaryDetail,price&crumb=" + java.net.URLEncoder.encode(yahooCrumb, "UTF-8");
            response.put("apiUrl", apiUrl);

            java.net.URL url = new java.net.URL(apiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Cookie", yahooCookies);

            int responseCode = conn.getResponseCode();
            response.put("httpStatus", responseCode);

            if (responseCode == 200) {
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }

                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(sb.toString());

                // Navigate to quoteSummary.result[0]
                com.fasterxml.jackson.databind.JsonNode result = root.path("quoteSummary").path("result");
                response.put("resultIsArray", result.isArray());
                response.put("resultSize", result.isArray() ? result.size() : 0);

                if (result.isArray() && result.size() > 0) {
                    com.fasterxml.jackson.databind.JsonNode data = result.get(0);

                    // Extract from summaryDetail
                    com.fasterxml.jackson.databind.JsonNode summaryDetail = data.path("summaryDetail");
                    response.put("hasSummaryDetail", !summaryDetail.isMissingNode());

                    if (summaryDetail.has("marketCap")) {
                        com.fasterxml.jackson.databind.JsonNode mcapNode = summaryDetail.path("marketCap");
                        response.put("marketCapNodeType", mcapNode.getNodeType().name());

                        double mcapRaw = 0;
                        if (mcapNode.isNumber()) {
                            mcapRaw = mcapNode.asDouble();
                            response.put("marketCapRaw", mcapRaw);
                        } else if (mcapNode.has("raw")) {
                            mcapRaw = mcapNode.path("raw").asDouble(0);
                            response.put("marketCapRaw", mcapRaw);
                            response.put("marketCapFormatted", mcapNode.path("fmt").asText(null));
                        } else {
                            response.put("marketCapNodeContent", mcapNode.toString().substring(0, Math.min(200, mcapNode.toString().length())));
                        }

                        if (mcapRaw > 0) {
                            response.put("marketCapCrores", Math.round((mcapRaw / 10000000) * 100.0) / 100.0);
                        }
                    } else {
                        response.put("hasMarketCap", false);
                        // Show what fields ARE available
                        java.util.Iterator<String> fieldNames = summaryDetail.fieldNames();
                        java.util.List<String> fields = new java.util.ArrayList<>();
                        while (fieldNames.hasNext()) {
                            fields.add(fieldNames.next());
                        }
                        response.put("summaryDetailFields", fields.toString());
                    }

                    // Extract from price module
                    com.fasterxml.jackson.databind.JsonNode priceNode = data.path("price");
                    response.put("hasPriceModule", !priceNode.isMissingNode());
                    if (priceNode.has("shortName")) {
                        response.put("companyName", priceNode.path("shortName").asText(null));
                    }
                    if (priceNode.has("regularMarketPrice")) {
                        com.fasterxml.jackson.databind.JsonNode priceVal = priceNode.path("regularMarketPrice");
                        if (priceVal.isNumber()) {
                            response.put("lastPrice", priceVal.asDouble(0));
                        } else if (priceVal.has("raw")) {
                            response.put("lastPrice", priceVal.path("raw").asDouble(0));
                        }
                    }

                    // Also check if price module has marketCap
                    if (priceNode.has("marketCap")) {
                        com.fasterxml.jackson.databind.JsonNode mcapNode = priceNode.path("marketCap");
                        double mcapRaw = 0;
                        if (mcapNode.isNumber()) {
                            mcapRaw = mcapNode.asDouble();
                        } else if (mcapNode.has("raw")) {
                            mcapRaw = mcapNode.path("raw").asDouble(0);
                        }
                        if (mcapRaw > 0) {
                            response.put("priceModuleMarketCapCrores", Math.round((mcapRaw / 10000000) * 100.0) / 100.0);
                        }
                    }
                }

                response.put("source", "Yahoo v10 quoteSummary");
            } else {
                response.put("error", "HTTP " + responseCode);

                // Try to read error body
                try {
                    java.io.InputStream errorStream = conn.getErrorStream();
                    if (errorStream != null) {
                        StringBuilder errSb = new StringBuilder();
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(errorStream))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                errSb.append(line);
                            }
                        }
                        String errBody = errSb.toString();
                        response.put("errorBody", errBody.substring(0, Math.min(500, errBody.length())));
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        } catch (Exception e) {
            response.put("error", e.getMessage());
            response.put("errorType", e.getClass().getSimpleName());
        }

        return ResponseEntity.ok(response);
    }

    private String toYahooSymbol(String ticker) {
        if (ticker == null) return null;
        String clean = ticker.trim().toUpperCase();
        if (clean.startsWith("NSE:")) {
            clean = clean.substring(4);
        } else if (clean.startsWith("BSE:")) {
            clean = clean.substring(4);
        }
        return clean + ".NS";
    }

    // Cache for Yahoo crumb (valid for ~1 hour)
    private static String yahooCrumb = null;
    private static String yahooCookies = null;
    private static long crumbExpiry = 0;

    /**
     * Get Yahoo Finance crumb and cookies for authenticated API calls.
     */
    private boolean refreshYahooCrumb() {
        try {
            // Step 1: Get cookies from Yahoo Finance main page
            java.net.URL mainUrl = new java.net.URL("https://finance.yahoo.com");
            java.net.HttpURLConnection conn1 = (java.net.HttpURLConnection) mainUrl.openConnection();
            conn1.setRequestMethod("GET");
            conn1.setConnectTimeout(10000);
            conn1.setReadTimeout(10000);
            conn1.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn1.setRequestProperty("Accept", "text/html,application/xhtml+xml");

            // Collect cookies
            StringBuilder cookieBuilder = new StringBuilder();
            java.util.Map<String, java.util.List<String>> headers = conn1.getHeaderFields();
            java.util.List<String> cookiesHeader = headers.get("Set-Cookie");
            if (cookiesHeader != null) {
                for (String cookie : cookiesHeader) {
                    if (cookieBuilder.length() > 0) cookieBuilder.append("; ");
                    cookieBuilder.append(cookie.split(";")[0]);
                }
            }
            conn1.disconnect();

            if (cookieBuilder.length() == 0) {
                return false;
            }

            yahooCookies = cookieBuilder.toString();

            // Step 2: Get crumb from crumb endpoint
            java.net.URL crumbUrl = new java.net.URL("https://query1.finance.yahoo.com/v1/test/getcrumb");
            java.net.HttpURLConnection conn2 = (java.net.HttpURLConnection) crumbUrl.openConnection();
            conn2.setRequestMethod("GET");
            conn2.setConnectTimeout(10000);
            conn2.setReadTimeout(10000);
            conn2.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn2.setRequestProperty("Cookie", yahooCookies);

            if (conn2.getResponseCode() == 200) {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn2.getInputStream()))) {
                    yahooCrumb = reader.readLine();
                }
            }
            conn2.disconnect();

            if (yahooCrumb != null && !yahooCrumb.isEmpty()) {
                crumbExpiry = System.currentTimeMillis() + 3600000; // 1 hour
                return true;
            }

        } catch (Exception e) {
            // Failed to get crumb
        }
        return false;
    }

    /**
     * Fetch market cap using Yahoo v10 quoteSummary API with crumb auth.
     */
    private Double fetchMarketCapFromYahooV10(String nseSymbol) {
        try {
            // Refresh crumb if expired
            if (yahooCrumb == null || System.currentTimeMillis() > crumbExpiry) {
                if (!refreshYahooCrumb()) {
                    return null;
                }
            }

            // Convert to Yahoo format: SYMBOL.NS
            String yahooSymbol = nseSymbol + ".NS";

            // Use v10 quoteSummary API with crumb
            String apiUrl = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/" + yahooSymbol
                    + "?modules=summaryDetail,price&crumb=" + java.net.URLEncoder.encode(yahooCrumb, "UTF-8");

            java.net.URL url = new java.net.URL(apiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Cookie", yahooCookies);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                // Crumb might be stale, reset and retry once
                yahooCrumb = null;
                return null;
            }

            StringBuilder response = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(response.toString());

            // Navigate to quoteSummary.result[0].summaryDetail.marketCap
            com.fasterxml.jackson.databind.JsonNode result = root.path("quoteSummary").path("result");
            if (result.isArray() && result.size() > 0) {
                com.fasterxml.jackson.databind.JsonNode summaryDetail = result.get(0).path("summaryDetail");

                if (summaryDetail.has("marketCap")) {
                    com.fasterxml.jackson.databind.JsonNode mcapNode = summaryDetail.path("marketCap");
                    double marketCapRaw = 0;

                    // Handle both formats: direct number OR object with "raw" field
                    if (mcapNode.isNumber()) {
                        marketCapRaw = mcapNode.asDouble();
                    } else if (mcapNode.has("raw")) {
                        marketCapRaw = mcapNode.path("raw").asDouble();
                    }

                    if (marketCapRaw > 0) {
                        // Convert to crores (1 crore = 10 million = 10,000,000)
                        double marketCapCrores = marketCapRaw / 10000000;
                        return Math.round(marketCapCrores * 100.0) / 100.0;
                    }
                }

                // Fallback: try price module
                com.fasterxml.jackson.databind.JsonNode priceNode = result.get(0).path("price");
                if (priceNode.has("marketCap")) {
                    com.fasterxml.jackson.databind.JsonNode mcapNode = priceNode.path("marketCap");
                    double marketCapRaw = 0;

                    if (mcapNode.isNumber()) {
                        marketCapRaw = mcapNode.asDouble();
                    } else if (mcapNode.has("raw")) {
                        marketCapRaw = mcapNode.path("raw").asDouble();
                    }

                    if (marketCapRaw > 0) {
                        double marketCapCrores = marketCapRaw / 10000000;
                        return Math.round(marketCapCrores * 100.0) / 100.0;
                    }
                }
            }

        } catch (Exception e) {
            // Silent fail
        }
        return null;
    }

    /**
     * Get stored sector data for a stock from database.
     * GET /api/yahoo/sector/get/{ticker}
     */
    @GetMapping("/sector/get/{ticker}")
    public ResponseEntity<Map<String, Object>> getSectorFromDb(@PathVariable String ticker) {
        Map<String, Object> response = new HashMap<>();

        String cleanTicker = ticker.toUpperCase();
        if (cleanTicker.startsWith("NSE:")) {
            cleanTicker = cleanTicker.substring(4);
        }

        response.put("ticker", cleanTicker);

        // Try to find with NSE: prefix
        StockAnalytics stock = yahooFinanceService.getStockByTicker("NSE:" + cleanTicker);
        if (stock == null) {
            stock = yahooFinanceService.getStockByTicker(cleanTicker);
        }

        if (stock != null) {
            response.put("name", stock.getName());
            response.put("sector", stock.getSector());
            response.put("industry", stock.getIndustry());
            response.put("sectorUpdated", stock.getSectorUpdated());
            response.put("found", true);
        } else {
            response.put("found", false);
        }

        return ResponseEntity.ok(response);
    }

    // ==================== SECTOR DATA ENDPOINTS ====================

    /**
     * Sync sector data for all stocks.
     * POST /api/yahoo/sector/sync-all
     */
    @PostMapping("/sector/sync-all")
    public ResponseEntity<Map<String, Object>> syncAllSectors() {
        int updated = yahooFinanceService.updateAllSectorData();
        Map<String, Object> response = new HashMap<>();
        response.put("status", "completed");
        response.put("updatedCount", updated);
        return ResponseEntity.ok(response);
    }

    /**
     * Sync sector data for a single stock.
     * POST /api/yahoo/sector/sync/{ticker}
     */
    @PostMapping("/sector/sync/{ticker}")
    public ResponseEntity<Map<String, Object>> syncSingleSector(@PathVariable String ticker) {
        boolean success = yahooFinanceService.fetchSectorData(ticker);
        Map<String, Object> response = new HashMap<>();
        response.put("ticker", ticker);
        response.put("success", success);
        return ResponseEntity.ok(response);
    }

    /**
     * Test sector mapping logic without fetching from API.
     * GET /api/yahoo/sector/map?industry=Computers - Software
     */
    @GetMapping("/sector/map")
    public ResponseEntity<Map<String, Object>> testSectorMapping(
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String sectorIndex) {
        Map<String, Object> response = new HashMap<>();
        response.put("inputIndustry", industry);
        response.put("inputSectorIndex", sectorIndex);

        String mappedSector = yahooFinanceService.testSectorMapping(sectorIndex, industry);
        response.put("mappedSector", mappedSector);

        return ResponseEntity.ok(response);
    }

    /**
     * Test sector API for a symbol using NSE India API.
     * GET /api/yahoo/sector/test/{symbol}
     * Example: /api/yahoo/sector/test/TCS
     */
    @GetMapping("/sector/test/{symbol}")
    public ResponseEntity<Map<String, Object>> testSectorApi(@PathVariable String symbol) {
        Map<String, Object> response = new HashMap<>();

        // Clean up symbol (remove NSE: prefix if present)
        String cleanSymbol = symbol.toUpperCase();
        if (cleanSymbol.startsWith("NSE:")) {
            cleanSymbol = cleanSymbol.substring(4);
        }
        if (cleanSymbol.endsWith(".NS")) {
            cleanSymbol = cleanSymbol.substring(0, cleanSymbol.length() - 3);
        }

        response.put("symbol", cleanSymbol);

        try {
            // Use NSE India API instead of Yahoo (Yahoo requires crumb authentication now)
            String apiUrl = "https://www.nseindia.com/api/quote-equity?symbol=" + cleanSymbol;

            java.net.URL url = new java.net.URL(apiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            response.put("httpStatus", responseCode);

            if (responseCode == 200) {
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }

                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(sb.toString());

                // Extract industry from info section
                if (root.has("info")) {
                    com.fasterxml.jackson.databind.JsonNode info = root.path("info");
                    response.put("industry", info.path("industry").asText(null));
                    response.put("companyName", info.path("companyName").asText(null));
                }

                // Extract sector index from metadata
                if (root.has("metadata")) {
                    com.fasterxml.jackson.databind.JsonNode metadata = root.path("metadata");
                    response.put("sectorIndex", metadata.path("pdSectorInd").asText(null));
                    response.put("listingDate", metadata.path("listingDate").asText(null));
                }

                response.put("source", "NSE India");
            } else {
                response.put("error", "HTTP " + responseCode);
            }
        } catch (Exception e) {
            response.put("error", e.getMessage());
            response.put("errorType", e.getClass().getSimpleName());
        }

        return ResponseEntity.ok(response);
    }

    // ==================== INDEX DATA ENDPOINTS ====================

    /**
     * Get NIFTY 50 index data including current price and returns.
     * GET /api/yahoo/index/nifty50
     */
    @GetMapping("/index/nifty50")
    public ResponseEntity<Map<String, Object>> getNifty50() {
        Map<String, Object> data = yahooFinanceService.fetchNifty50Data();
        return ResponseEntity.ok(data);
    }

    /**
     * Get any index data by symbol.
     * GET /api/yahoo/index/{symbol}
     * Examples: ^NSEI (NIFTY 50), ^NSEBANK (Bank NIFTY), ^BSESN (SENSEX)
     */
    @GetMapping("/index/{symbol}")
    public ResponseEntity<Map<String, Object>> getIndexData(@PathVariable String symbol) {
        Map<String, Object> data = yahooFinanceService.fetchIndexData(symbol, symbol);
        return ResponseEntity.ok(data);
    }

    /**
     * Get all major Indian indices for portfolio comparison.
     * GET /api/yahoo/indices/all
     * Returns: NIFTY 50, NIFTY 500, NIFTY Midcap 100, NIFTY Smallcap 100
     */
    @GetMapping("/indices/all")
    public ResponseEntity<Map<String, Object>> getAllIndices() {
        Map<String, Object> data = yahooFinanceService.fetchAllIndicesData();
        return ResponseEntity.ok(data);
    }

    /**
     * Get historical daily data for all major indices.
     * GET /api/yahoo/indices/historical
     * GET /api/yahoo/indices/historical?fromDate=2024-12-22
     * Returns normalized values (base 100) for comparison charts.
     * If fromDate is provided, data is filtered and normalized from that date.
     */
    @GetMapping("/indices/historical")
    public ResponseEntity<Map<String, Object>> getAllIndicesHistorical(
            @RequestParam(required = false) String fromDate) {
        Map<String, Object> data = yahooFinanceService.fetchAllIndicesHistoricalData(fromDate);
        return ResponseEntity.ok(data);
    }

    /**
     * Debug endpoint to test Yahoo Finance API directly.
     * GET /api/yahoo/test/{symbol}
     * Example: /api/yahoo/test/TCS.NS
     */
    @GetMapping("/test/{symbol}")
    public ResponseEntity<Map<String, Object>> testYahoo(@PathVariable String symbol) {
        Map<String, Object> response = new HashMap<>();
        response.put("symbol", symbol);

        try {
            // Use v8 chart API with proper headers
            String apiUrl = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
                    + "?interval=1d&range=5d";

            java.net.URL url = new java.net.URL(apiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            response.put("httpStatus", responseCode);

            if (responseCode == 200) {
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }

                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(sb.toString());
                com.fasterxml.jackson.databind.JsonNode meta = root.path("chart").path("result").get(0).path("meta");

                response.put("regularMarketPrice", meta.path("regularMarketPrice").asDouble());
                response.put("fiftyTwoWeekHigh", meta.path("fiftyTwoWeekHigh").asDouble());
                response.put("fiftyTwoWeekLow", meta.path("fiftyTwoWeekLow").asDouble());
                response.put("currency", meta.path("currency").asText());
            } else {
                response.put("error", "HTTP " + responseCode);
            }
        } catch (Exception e) {
            response.put("error", e.getMessage());
            response.put("errorType", e.getClass().getSimpleName());
        }

        return ResponseEntity.ok(response);
    }

    // ==================== GOOGLE SHEET MARKET CAP HELPER ====================

    private static final String MCAP_HELPER_SHEET = "MarketCapHelper";

    /**
     * Step 1: Populate the MarketCapHelper sheet with YAHOO tickers and GOOGLEFINANCE formulas.
     * POST /api/yahoo/sheet/populate-mcap?limit=100
     *
     * This writes rows like:
     * | TICKER | =GOOGLEFINANCE("NSE:TICKER", "marketcap") |
     *
     * After calling this, wait 30-60 seconds for Google to calculate the formulas,
     * then call /api/yahoo/sheet/read-mcap to read back the values.
     */
    @PostMapping("/sheet/populate-mcap")
    public ResponseEntity<Map<String, Object>> populateMarketCapSheet(
            @RequestParam(defaultValue = "100") int limit) {

        Map<String, Object> response = new HashMap<>();

        try {
            // Get YAHOO stocks without market cap
            List<StockAnalytics> yahooStocks = repository.findBySource("YAHOO");
            List<StockAnalytics> toProcess = yahooStocks.stream()
                    .filter(s -> s.getMarketCap() == null)
                    .limit(Math.min(limit, 1000)) // Max 1000 at a time
                    .collect(java.util.stream.Collectors.toList());

            if (toProcess.isEmpty()) {
                response.put("status", "completed");
                response.put("message", "No YAHOO stocks without market cap found");
                response.put("tickersWritten", 0);
                return ResponseEntity.ok(response);
            }

            // Build rows with GOOGLEFINANCE formulas
            // Column A: Ticker (clean, without NSE: prefix)
            // Column B: =GOOGLEFINANCE("NSE:TICKER", "marketcap") formula
            List<List<Object>> rows = new java.util.ArrayList<>();

            // Header row
            rows.add(java.util.Arrays.asList("Ticker", "MarketCap"));

            for (StockAnalytics stock : toProcess) {
                String ticker = stock.getTicker();
                if (ticker == null) continue;

                // Clean ticker - remove NSE: prefix
                String cleanTicker = ticker.toUpperCase();
                if (cleanTicker.startsWith("NSE:")) {
                    cleanTicker = cleanTicker.substring(4);
                }

                // Create GOOGLEFINANCE formula
                String formula = "=GOOGLEFINANCE(\"NSE:" + cleanTicker + "\", \"marketcap\")";

                rows.add(java.util.Arrays.asList(cleanTicker, formula));
            }

            // Clear existing data and write new rows
            String range = MCAP_HELPER_SHEET + "!A1:B" + rows.size();
            try {
                googleSheetService.clearRange(MCAP_HELPER_SHEET + "!A:B");
            } catch (Exception e) {
                // Sheet might not exist, that's OK - write will create it
            }

            googleSheetService.writeRowsWithFormulas(range, rows);

            response.put("status", "success");
            response.put("tickersWritten", rows.size() - 1); // Exclude header
            response.put("message", "Formulas written to sheet. Wait 30-60 seconds, then call /api/yahoo/sheet/read-mcap");
            response.put("sheetName", MCAP_HELPER_SHEET);
            response.put("totalYahooWithoutMcap", yahooStocks.stream().filter(s -> s.getMarketCap() == null).count());

        } catch (Exception e) {
            response.put("status", "error");
            response.put("error", e.getMessage());
            response.put("errorType", e.getClass().getSimpleName());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Step 2: Read calculated market cap values from the sheet and update the database.
     * POST /api/yahoo/sheet/read-mcap
     *
     * Call this 30-60 seconds after /api/yahoo/sheet/populate-mcap to give
     * Google time to calculate the GOOGLEFINANCE formulas.
     */
    @PostMapping("/sheet/read-mcap")
    public ResponseEntity<Map<String, Object>> readMarketCapFromSheet() {
        Map<String, Object> response = new HashMap<>();

        try {
            // Read values from the sheet (skip header)
            List<List<Object>> values = googleSheetService.readRange(MCAP_HELPER_SHEET + "!A2:B1000");

            if (values == null || values.isEmpty()) {
                response.put("status", "completed");
                response.put("message", "No data found in sheet. Run /api/yahoo/sheet/populate-mcap first.");
                response.put("updatedCount", 0);
                return ResponseEntity.ok(response);
            }

            int updated = 0;
            int failed = 0;
            int notFound = 0;

            for (List<Object> row : values) {
                if (row.size() < 2) continue;

                String ticker = row.get(0) != null ? row.get(0).toString().trim() : null;
                Object mcapValue = row.get(1);

                if (ticker == null || ticker.isEmpty()) continue;

                // Parse market cap value
                Double marketCapRaw = null;
                if (mcapValue != null) {
                    String mcapStr = mcapValue.toString().trim();
                    // Skip if it's an error like #N/A or formula still calculating
                    if (mcapStr.startsWith("#") || mcapStr.isEmpty()) {
                        failed++;
                        continue;
                    }
                    try {
                        marketCapRaw = Double.parseDouble(mcapStr);
                    } catch (NumberFormatException e) {
                        failed++;
                        continue;
                    }
                }

                if (marketCapRaw == null || marketCapRaw <= 0) {
                    failed++;
                    continue;
                }

                // Convert from raw value to crores (1 crore = 10 million)
                double marketCapCrores = marketCapRaw / 10000000;
                marketCapCrores = Math.round(marketCapCrores * 100.0) / 100.0;

                // Find and update the stock in database
                // Try with NSE: prefix first (ticker is the ID)
                StockAnalytics stock = repository.findById("NSE:" + ticker).orElse(null);
                if (stock == null) {
                    stock = repository.findById(ticker).orElse(null);
                }

                if (stock != null) {
                    stock.setMarketCap(marketCapCrores);
                    stock.setLastUpdated(java.time.LocalDateTime.now());
                    repository.save(stock);
                    updated++;
                } else {
                    notFound++;
                }
            }

            response.put("status", "completed");
            response.put("updatedCount", updated);
            response.put("failedCount", failed);
            response.put("notFoundCount", notFound);
            response.put("totalProcessed", values.size());

            // Count remaining without market cap
            List<StockAnalytics> yahooStocks = repository.findBySource("YAHOO");
            long remaining = yahooStocks.stream().filter(s -> s.getMarketCap() == null).count();
            response.put("remainingWithoutMcap", remaining);

        } catch (Exception e) {
            response.put("status", "error");
            response.put("error", e.getMessage());
            response.put("errorType", e.getClass().getSimpleName());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Combined endpoint: Populate sheet, wait, and read back values.
     * POST /api/yahoo/sheet/sync-mcap?limit=100&waitSeconds=45
     *
     * This is a convenience endpoint that runs both steps with a configurable wait.
     */
    @PostMapping("/sheet/sync-mcap")
    public ResponseEntity<Map<String, Object>> syncMarketCapViaSheet(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "45") int waitSeconds) {

        Map<String, Object> response = new HashMap<>();

        try {
            // Step 1: Populate sheet
            List<StockAnalytics> yahooStocks = repository.findBySource("YAHOO");
            List<StockAnalytics> toProcess = yahooStocks.stream()
                    .filter(s -> s.getMarketCap() == null)
                    .limit(Math.min(limit, 1000))
                    .collect(java.util.stream.Collectors.toList());

            if (toProcess.isEmpty()) {
                response.put("status", "completed");
                response.put("message", "No YAHOO stocks without market cap found");
                response.put("updatedCount", 0);
                return ResponseEntity.ok(response);
            }

            // Build rows with GOOGLEFINANCE formulas
            List<List<Object>> rows = new java.util.ArrayList<>();
            rows.add(java.util.Arrays.asList("Ticker", "MarketCap"));

            for (StockAnalytics stock : toProcess) {
                String ticker = stock.getTicker();
                if (ticker == null) continue;

                String cleanTicker = ticker.toUpperCase();
                if (cleanTicker.startsWith("NSE:")) {
                    cleanTicker = cleanTicker.substring(4);
                }

                String formula = "=GOOGLEFINANCE(\"NSE:" + cleanTicker + "\", \"marketcap\")";
                rows.add(java.util.Arrays.asList(cleanTicker, formula));
            }

            // Clear and write
            String range = MCAP_HELPER_SHEET + "!A1:B" + rows.size();
            try {
                googleSheetService.clearRange(MCAP_HELPER_SHEET + "!A:B");
            } catch (Exception e) {
                // Ignore
            }
            googleSheetService.writeRowsWithFormulas(range, rows);

            response.put("tickersWritten", rows.size() - 1);

            // Wait for formulas to calculate
            response.put("waitSeconds", waitSeconds);
            Thread.sleep(waitSeconds * 1000L);

            // Step 2: Read back values
            List<List<Object>> values = googleSheetService.readRange(MCAP_HELPER_SHEET + "!A2:B1000");

            int updated = 0;
            int failed = 0;

            if (values != null) {
                for (List<Object> row : values) {
                    if (row.size() < 2) continue;

                    String ticker = row.get(0) != null ? row.get(0).toString().trim() : null;
                    Object mcapValue = row.get(1);

                    if (ticker == null || ticker.isEmpty()) continue;

                    Double marketCapRaw = null;
                    if (mcapValue != null) {
                        String mcapStr = mcapValue.toString().trim();
                        if (mcapStr.startsWith("#") || mcapStr.isEmpty()) {
                            failed++;
                            continue;
                        }
                        try {
                            marketCapRaw = Double.parseDouble(mcapStr);
                        } catch (NumberFormatException e) {
                            failed++;
                            continue;
                        }
                    }

                    if (marketCapRaw == null || marketCapRaw <= 0) {
                        failed++;
                        continue;
                    }

                    double marketCapCrores = Math.round((marketCapRaw / 10000000) * 100.0) / 100.0;

                    StockAnalytics stock = repository.findById("NSE:" + ticker).orElse(null);
                    if (stock == null) {
                        stock = repository.findById(ticker).orElse(null);
                    }

                    if (stock != null) {
                        stock.setMarketCap(marketCapCrores);
                        stock.setLastUpdated(java.time.LocalDateTime.now());
                        repository.save(stock);
                        updated++;
                    }
                }
            }

            response.put("status", "completed");
            response.put("updatedCount", updated);
            response.put("failedCount", failed);

            long remaining = repository.findBySource("YAHOO").stream()
                    .filter(s -> s.getMarketCap() == null).count();
            response.put("remainingWithoutMcap", remaining);

        } catch (Exception e) {
            response.put("status", "error");
            response.put("error", e.getMessage());
        }

        return ResponseEntity.ok(response);
    }
}
