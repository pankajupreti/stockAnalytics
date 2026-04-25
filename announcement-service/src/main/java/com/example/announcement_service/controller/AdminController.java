package com.example.announcement_service.controller;

import com.example.announcement_service.model.TickerMapping;
import com.example.announcement_service.repository.AnnouncementRepository;
import com.example.announcement_service.service.StockMatchingService;
import com.example.announcement_service.service.TickerMappingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin controller for managing ticker mappings.
 * Endpoints to import BSE equity list and manage mappings.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final TickerMappingService tickerMappingService;
    private final StockMatchingService stockMatchingService;
    private final AnnouncementRepository announcementRepository;

    /**
     * Upload BSE Equity List CSV to populate ticker mappings.
     * CSV format from BSE: Security Code, Security Id, Security Name, Status, Group, Face Value, ISIN No, Industry, Instrument
     *
     * POST /api/admin/import/bse-equity-list
     * Content-Type: multipart/form-data
     */
    @PostMapping("/import/bse-equity-list")
    public ResponseEntity<Map<String, Object>> importBseEquityList(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }

        try {
            List<TickerMapping> mappings = parseBseEquityListCsv(file);
            int savedCount = tickerMappingService.saveMappings(mappings);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "BSE equity list imported successfully",
                    "totalParsed", mappings.size(),
                    "savedCount", savedCount,
                    "totalMappings", tickerMappingService.getMappingCount()
            ));
        } catch (Exception e) {
            log.error("Failed to import BSE equity list: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Failed to import: " + e.getMessage()
            ));
        }
    }

    /**
     * Upload a simple CSV with scrip code to NSE ticker mappings.
     * CSV format: scripCode,nseTicker,companyName (header optional)
     *
     * POST /api/admin/import/ticker-mappings
     */
    @PostMapping("/import/ticker-mappings")
    public ResponseEntity<Map<String, Object>> importTickerMappings(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }

        try {
            List<TickerMapping> mappings = parseSimpleMappingCsv(file);
            int savedCount = tickerMappingService.saveMappings(mappings);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Ticker mappings imported successfully",
                    "totalParsed", mappings.size(),
                    "savedCount", savedCount
            ));
        } catch (Exception e) {
            log.error("Failed to import ticker mappings: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Failed to import: " + e.getMessage()
            ));
        }
    }

    /**
     * Add a single ticker mapping.
     * POST /api/admin/mappings
     */
    @PostMapping("/mappings")
    public ResponseEntity<TickerMapping> addMapping(@RequestBody Map<String, String> request) {
        String scripCode = request.get("scripCode");
        String nseTicker = request.get("nseTicker");
        String companyName = request.get("companyName");
        String isin = request.get("isin");

        if (scripCode == null || scripCode.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        TickerMapping saved = tickerMappingService.saveMapping(scripCode, nseTicker, companyName, isin);
        return ResponseEntity.ok(saved);
    }

    /**
     * Refresh the in-memory cache from database.
     * POST /api/admin/mappings/refresh
     */
    @PostMapping("/mappings/refresh")
    public ResponseEntity<Map<String, Object>> refreshMappings() {
        int count = tickerMappingService.refreshCache();
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Cache refreshed",
                "mappingCount", count
        ));
    }

    /**
     * Get mapping statistics.
     * GET /api/admin/mappings/stats
     */
    @GetMapping("/mappings/stats")
    public ResponseEntity<Map<String, Object>> getMappingStats() {
        return ResponseEntity.ok(Map.of(
                "totalMappings", tickerMappingService.getMappingCount(),
                "cachedMappings", tickerMappingService.getAllMappings().size()
        ));
    }

    /**
     * Match stock analytics dump with BSE equity list.
     * This endpoint takes two CSV files:
     * 1. stockAnalytics.csv - Export from stock_analytics table (columns: ticker, name)
     * 2. bseEquity.csv - BSE equity list (columns: scripCode, bseTicker, companyName, isin, industry)
     *
     * POST /api/admin/match-stocks
     * Content-Type: multipart/form-data
     */
    @PostMapping("/match-stocks")
    public ResponseEntity<Map<String, Object>> matchStocks(
            @RequestParam("stockAnalytics") MultipartFile stockAnalyticsFile,
            @RequestParam("bseEquity") MultipartFile bseEquityFile) {

        if (stockAnalyticsFile.isEmpty() || bseEquityFile.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Both files are required"));
        }

        try {
            // Parse stock analytics CSV (ticker, name)
            List<Map<String, String>> stockAnalyticsList = parseStockAnalyticsCsv(stockAnalyticsFile);

            // Parse BSE equity list CSV
            List<Map<String, String>> bseEquityList = parseBseEquityForMatching(bseEquityFile);

            // Perform matching
            StockMatchingService.MatchResult result = stockMatchingService.matchStocks(stockAnalyticsList, bseEquityList);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Stock matching completed",
                    "stockAnalyticsCount", stockAnalyticsList.size(),
                    "bseEquityCount", bseEquityList.size(),
                    "matched", result.matched(),
                    "noMatch", result.noMatch(),
                    "savedMappings", result.savedCount(),
                    "totalMappings", tickerMappingService.getMappingCount()
            ));
        } catch (Exception e) {
            log.error("Failed to match stocks: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Failed to match: " + e.getMessage()
            ));
        }
    }

    /**
     * Parse stock analytics CSV (export from database).
     * Expected columns: ticker, name (or ticker, company_name)
     */
    private List<Map<String, String>> parseStockAnalyticsCsv(MultipartFile file) throws Exception {
        List<Map<String, String>> result = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            boolean headerSkipped = false;
            int tickerCol = 0, nameCol = 1;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);

                // Detect header and column positions
                if (!headerSkipped) {
                    headerSkipped = true;
                    for (int i = 0; i < parts.length; i++) {
                        String col = cleanValue(parts[i]).toLowerCase();
                        if (col.equals("ticker") || col.equals("symbol")) tickerCol = i;
                        if (col.equals("name") || col.equals("company_name") || col.equals("companyname")) nameCol = i;
                    }
                    continue;
                }

                if (parts.length <= tickerCol) continue;

                String ticker = cleanValue(parts[tickerCol]);
                String name = parts.length > nameCol ? cleanValue(parts[nameCol]) : "";

                if (!ticker.isEmpty()) {
                    Map<String, String> row = new HashMap<>();
                    row.put("ticker", ticker.toUpperCase());
                    row.put("name", name);
                    result.add(row);
                }
            }
        }

        log.info("Parsed {} stocks from stock analytics CSV", result.size());
        return result;
    }

    /**
     * Parse BSE equity list for matching.
     * BSE CSV headers: Security Code, Issuer Name, Security Id, Security Name, Status, Group, Face Value, ISIN No, Instrument
     * We need: Security Code (scripCode), Security Id (ticker), Security Name (companyName), ISIN No
     */
    private List<Map<String, String>> parseBseEquityForMatching(MultipartFile file) throws Exception {
        List<Map<String, String>> result = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            boolean headerParsed = false;
            // Column indices (will be detected from header)
            int colScripCode = 0, colSecurityId = 2, colCompanyName = 3, colIsin = 7;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);

                // Detect header and column positions
                if (!headerParsed) {
                    headerParsed = true;
                    // Find column indices from header
                    for (int i = 0; i < parts.length; i++) {
                        String col = cleanValue(parts[i]).toLowerCase();
                        if (col.equals("security code")) colScripCode = i;
                        else if (col.equals("security id")) colSecurityId = i;
                        else if (col.equals("security name")) colCompanyName = i;
                        else if (col.equals("isin no")) colIsin = i;
                    }
                    log.info("BSE CSV columns detected - scripCode:{}, securityId:{}, companyName:{}, isin:{}",
                            colScripCode, colSecurityId, colCompanyName, colIsin);
                    continue;
                }

                if (parts.length <= Math.max(Math.max(colScripCode, colSecurityId), Math.max(colCompanyName, colIsin))) {
                    continue;
                }

                String scripCode = cleanValue(parts[colScripCode]);
                String securityId = cleanValue(parts[colSecurityId]);
                String companyName = parts.length > colCompanyName ? cleanValue(parts[colCompanyName]) : "";
                String isin = parts.length > colIsin ? cleanValue(parts[colIsin]) : null;

                if (!scripCode.isEmpty() && !securityId.isEmpty()) {
                    Map<String, String> row = new HashMap<>();
                    row.put("scripCode", scripCode);
                    row.put("securityId", securityId.toUpperCase());
                    row.put("companyName", companyName);
                    row.put("isin", isin);
                    result.add(row);
                }
            }
        }

        log.info("Parsed {} stocks from BSE equity CSV", result.size());
        return result;
    }

    /**
     * Parse BSE Equity List CSV.
     * Expected columns: Security Code, Security Id, Security Name, Status, Group, Face Value, ISIN No, Industry
     */
    private List<TickerMapping> parseBseEquityListCsv(MultipartFile file) throws Exception {
        List<TickerMapping> mappings = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            boolean headerSkipped = false;
            int lineNum = 0;

            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty()) continue;

                // Skip header row
                if (!headerSkipped && (line.toLowerCase().contains("security code") ||
                        line.toLowerCase().contains("scrip"))) {
                    headerSkipped = true;
                    continue;
                }

                String[] parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
                if (parts.length < 7) {
                    log.debug("Skipping line {} - insufficient columns: {}", lineNum, line);
                    continue;
                }

                try {
                    String scripCode = cleanValue(parts[0]);
                    String bseTicker = cleanValue(parts[1]);  // Security Id (BSE ticker)
                    String companyName = cleanValue(parts[2]);
                    String status = parts.length > 3 ? cleanValue(parts[3]) : "";
                    String isin = parts.length > 6 ? cleanValue(parts[6]) : null;
                    String industry = parts.length > 7 ? cleanValue(parts[7]) : null;

                    // Skip suspended/delisted stocks
                    if (status.equalsIgnoreCase("Suspended") || status.equalsIgnoreCase("Delisted")) {
                        continue;
                    }

                    // Use BSE ticker as NSE ticker (they're often the same for major stocks)
                    // You can enhance this later with actual NSE mapping
                    TickerMapping mapping = TickerMapping.builder()
                            .scripCode(scripCode)
                            .bseTicker(bseTicker)
                            .nseTicker(bseTicker)  // Default to BSE ticker, can be updated later
                            .companyName(companyName)
                            .isin(isin)
                            .industry(industry)
                            .active(true)
                            .build();

                    mappings.add(mapping);
                } catch (Exception e) {
                    log.debug("Failed to parse line {}: {} - {}", lineNum, line, e.getMessage());
                }
            }
        }

        log.info("Parsed {} mappings from BSE equity list", mappings.size());
        return mappings;
    }

    /**
     * Parse simple mapping CSV (scripCode,nseTicker,companyName).
     */
    private List<TickerMapping> parseSimpleMappingCsv(MultipartFile file) throws Exception {
        List<TickerMapping> mappings = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            boolean headerSkipped = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Skip header
                if (!headerSkipped && line.toLowerCase().contains("scrip")) {
                    headerSkipped = true;
                    continue;
                }

                String[] parts = line.split(",");
                if (parts.length < 2) continue;

                String scripCode = cleanValue(parts[0]);
                String nseTicker = cleanValue(parts[1]);
                String companyName = parts.length > 2 ? cleanValue(parts[2]) : null;

                if (scripCode.isEmpty() || nseTicker.isEmpty()) continue;

                mappings.add(TickerMapping.builder()
                        .scripCode(scripCode)
                        .nseTicker(nseTicker)
                        .companyName(companyName)
                        .active(true)
                        .build());
            }
        }

        return mappings;
    }

    private String cleanValue(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("^\"|\"$", "").trim();
    }

    /**
     * Find announcements with missing NSE ticker mappings.
     * Returns a list of scrip codes and company names that need mappings.
     * GET /api/admin/missing-mappings?days=30
     */
    @GetMapping("/missing-mappings")
    public ResponseEntity<Map<String, Object>> findMissingMappings(
            @RequestParam(defaultValue = "30") int days) {

        // Find announcements with scripCode but no nseTicker
        java.time.LocalDateTime afterDate = java.time.LocalDateTime.now().minusDays(days);

        List<Object[]> missing = announcementRepository.findMissingMappings(afterDate);

        List<Map<String, String>> missingList = new ArrayList<>();
        for (Object[] row : missing) {
            String scripCode = row[0] != null ? row[0].toString() : "";
            String companyName = row[1] != null ? row[1].toString() : "";
            long count = row[2] != null ? ((Number) row[2]).longValue() : 0;

            if (!scripCode.isEmpty()) {
                Map<String, String> entry = new HashMap<>();
                entry.put("scripCode", scripCode);
                entry.put("companyName", companyName);
                entry.put("announcementCount", String.valueOf(count));
                missingList.add(entry);
            }
        }

        return ResponseEntity.ok(Map.of(
                "days", days,
                "missingCount", missingList.size(),
                "missing", missingList
        ));
    }

    /**
     * Auto-discover ticker mappings from BSE website for missing scrip codes.
     * POST /api/admin/auto-discover-mappings?days=30&limit=50
     */
    @PostMapping("/auto-discover-mappings")
    public ResponseEntity<Map<String, Object>> autoDiscoverMappings(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "50") int limit) {

        java.time.LocalDateTime afterDate = java.time.LocalDateTime.now().minusDays(days);

        List<Object[]> missing = announcementRepository.findMissingMappings(afterDate);

        int discovered = 0;
        int failed = 0;
        List<Map<String, String>> results = new ArrayList<>();

        for (int i = 0; i < Math.min(missing.size(), limit); i++) {
            Object[] row = missing.get(i);
            String scripCode = row[0] != null ? row[0].toString() : "";
            String companyName = row[1] != null ? row[1].toString() : "";

            if (scripCode.isEmpty()) continue;

            try {
                // Try to fetch ticker from BSE API
                String nseTicker = fetchTickerFromBse(scripCode);

                if (nseTicker != null && !nseTicker.isEmpty()) {
                    tickerMappingService.saveMapping(scripCode, nseTicker, companyName, null);
                    discovered++;

                    Map<String, String> result = new HashMap<>();
                    result.put("scripCode", scripCode);
                    result.put("nseTicker", nseTicker);
                    result.put("companyName", companyName);
                    result.put("status", "discovered");
                    results.add(result);

                    log.info("Discovered mapping: {} -> {}", scripCode, nseTicker);
                } else {
                    failed++;
                    Map<String, String> result = new HashMap<>();
                    result.put("scripCode", scripCode);
                    result.put("companyName", companyName);
                    result.put("status", "not_found");
                    results.add(result);
                }

                // Rate limiting
                Thread.sleep(200);
            } catch (Exception e) {
                failed++;
                log.warn("Failed to discover ticker for {}: {}", scripCode, e.getMessage());
            }
        }

        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "totalMissing", missing.size(),
                "processed", Math.min(missing.size(), limit),
                "discovered", discovered,
                "failed", failed,
                "results", results
        ));
    }

    /**
     * Fetch NSE ticker from BSE API for a given scrip code.
     */
    private String fetchTickerFromBse(String scripCode) {
        try {
            // BSE API to get stock info
            String apiUrl = "https://api.bseindia.com/BseIndiaAPI/api/StockReachGraph/w?scripcode=" + scripCode + "&flag=0&fromdate=&todate=&seression=";

            java.net.URL url = new java.net.URL(apiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Referer", "https://www.bseindia.com/");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
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

            // Parse response to find ticker/securityId
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(response.toString());

            // BSE API returns secid as the ticker symbol
            if (root.has("Comp_secid")) {
                return root.get("Comp_secid").asText();
            } else if (root.has("secid")) {
                return root.get("secid").asText();
            } else if (root.has("SC_ID")) {
                return root.get("SC_ID").asText();
            }

            return null;

        } catch (Exception e) {
            log.debug("Failed to fetch ticker from BSE for {}: {}", scripCode, e.getMessage());
            return null;
        }
    }
}
