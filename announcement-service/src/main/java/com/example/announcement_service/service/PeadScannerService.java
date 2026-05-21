package com.example.announcement_service.service;

import com.example.announcement_service.client.ReportingServiceClient;
import com.example.announcement_service.client.ResultsServiceClient;
import com.example.announcement_service.dto.PeadScannerResponse;
import com.example.announcement_service.dto.PeadStockDTO;
import com.example.announcement_service.model.Announcement;
import com.example.announcement_service.model.AnnouncementPriceCache;
import com.example.announcement_service.repository.AnnouncementPriceCacheRepository;
import com.example.announcement_service.repository.AnnouncementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * PEAD (Post Earnings Announcement Drift) Scanner Service.
 *
 * Analyzes stocks that have recently announced quarterly results and calculates
 * the price drift since the announcement. Helps identify momentum opportunities
 * where stocks continue to drift in the direction of their earnings surprise.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PeadScannerService {

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementPriceCacheRepository priceCacheRepository;
    private final ResultsServiceClient resultsServiceClient;
    private final ReportingServiceClient reportingServiceClient;
    private final TickerMappingService tickerMappingService;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Cache for historical prices to avoid repeated API calls
    private final Map<String, Map<LocalDate, Double>> priceCache = new ConcurrentHashMap<>();

    // Keywords that indicate a financial results announcement
    private static final List<String> FINANCIAL_RESULT_KEYWORDS = List.of(
            "financial result",
            "quarterly result",
            "un-audited financial",
            "unaudited financial",
            "audited financial",
            "standalone financial",
            "consolidated financial",
            "outcome of board meeting",
            "outcome of the board meeting",
            "board meeting outcome",
            "results for the quarter",
            "results for quarter",
            "board approves dividend"
    );

    /**
     * Get current quarter and fiscal year based on Indian fiscal year (April-March).
     * Returns the quarter whose results are currently being announced.
     *
     * Results announcement seasons (approximate):
     *   Jan-Mar: Q3 results (Oct-Dec quarter)
     *   Apr-Jun: Q4 results (Jan-Mar quarter)
     *   Jul-Sep: Q1 results (Apr-Jun quarter)
     *   Oct-Dec: Q2 results (Jul-Sep quarter)
     *
     * Indian FY convention: FY2026 = April 2025 to March 2026.
     * So Q3 FY2026 = Oct-Dec 2025, Q4 FY2026 = Jan-Mar 2026.
     */
    public Map<String, Object> getCurrentQuarterInfo() {
        LocalDateTime now = LocalDateTime.now();
        int month = now.getMonthValue();
        int year = now.getYear();

        String quarter;
        int fiscalYear;

        switch (month) {
            case 1: case 2: case 3:
                quarter = "Q3";
                fiscalYear = year;
                break;
            case 4: case 5: case 6:
                quarter = "Q4";
                fiscalYear = year;
                break;
            case 7: case 8: case 9:
                quarter = "Q1";
                fiscalYear = year + 1;
                break;
            default: // 10, 11, 12
                quarter = "Q2";
                fiscalYear = year + 1;
                break;
        }

        return Map.of("quarter", quarter, "fiscalYear", fiscalYear);
    }

    /**
     * Get previous quarter and fiscal year relative to the given quarter.
     */
    private Map<String, Object> getPreviousQuarterInfo(String quarter, int fiscalYear) {
        switch (quarter) {
            case "Q1": return Map.of("quarter", "Q4", "fiscalYear", fiscalYear - 1);
            case "Q2": return Map.of("quarter", "Q1", "fiscalYear", fiscalYear);
            case "Q3": return Map.of("quarter", "Q2", "fiscalYear", fiscalYear);
            case "Q4": return Map.of("quarter", "Q3", "fiscalYear", fiscalYear);
            default:   return Map.of("quarter", "Q3", "fiscalYear", fiscalYear);
        }
    }

    /**
     * Run the PEAD scanner with given filters.
     *
     * @param minRevenueYoY Minimum Revenue YoY growth %
     * @param minRevenueQoQ Minimum Revenue QoQ growth %
     * @param minPatYoY Minimum PAT YoY growth %
     * @param minPatQoQ Minimum PAT QoQ growth %
     * @param minPbtYoY Minimum PBT YoY growth %
     * @param minPbtQoQ Minimum PBT QoQ growth %
     * @param minMarketCap Minimum market cap (Crores)
     * @param maxMarketCap Maximum market cap (Crores)
     * @param minPctChange Minimum % change since results
     * @param currentQuarterOnly Filter to current quarter results only
     * @param quarter Specific quarter (Q1/Q2/Q3/Q4)
     * @param fiscalYear Specific fiscal year
     * @param resultType "consolidated" or "standalone"
     * @param sortBy Sort field: "pctChangeSinceResults", "patYoY", "revenueYoY", "announcementDate"
     * @param sortOrder "desc" or "asc"
     * @param limit Max results
     * @return PeadScannerResponse with matching stocks
     */
    public PeadScannerResponse scan(
            Double minRevenueYoY,
            Double minRevenueQoQ,
            Double minPatYoY,
            Double minPatQoQ,
            Double minPbtYoY,
            Double minPbtQoQ,
            Double minMarketCap,
            Double maxMarketCap,
            Double minPctChange,
            boolean currentQuarterOnly,
            String quarter,
            Integer fiscalYear,
            String resultType,
            String sortBy,
            String sortOrder,
            int limit) {

        log.info("Running PEAD scanner: patYoY>={}, revYoY>={}, pbtYoY>={}, currentQtr={}, sortBy={}",
                minPatYoY, minRevenueYoY, minPbtYoY, currentQuarterOnly, sortBy);

        long scanStart = System.currentTimeMillis();

        try {
            // Step 1: Fetch stocks with good results from Python service
            long step1Start = System.currentTimeMillis();
            var goodResultsMono = resultsServiceClient.fetchGoodResults(
                    minPatYoY,
                    minRevenueYoY,
                    minPatQoQ,
                    minRevenueQoQ,
                    minPbtYoY,
                    minPbtQoQ,
                    currentQuarterOnly,
                    quarter,
                    fiscalYear,
                    resultType,
                    90,  // Look back 90 days for results data
                    500  // Get more results initially, will filter later
            );

            var goodResults = goodResultsMono.block();

            // Fallback: if currentQuarterOnly returned no results, try previous quarter.
            // This handles the transition period (e.g., early April when Q4 results haven't
            // started coming in yet, but Q3 results are still relevant).
            if (currentQuarterOnly
                    && (goodResults == null || goodResults.getStocks() == null || goodResults.getStocks().isEmpty())) {
                Map<String, Object> currentQtr = getCurrentQuarterInfo();
                String curQ = (String) currentQtr.get("quarter");
                int curFY = (int) currentQtr.get("fiscalYear");
                Map<String, Object> prevQtr = getPreviousQuarterInfo(curQ, curFY);
                String prevQ = (String) prevQtr.get("quarter");
                int prevFY = (int) prevQtr.get("fiscalYear");

                log.info("No results for {} FY{}, falling back to {} FY{}", curQ, curFY, prevQ, prevFY);

                goodResultsMono = resultsServiceClient.fetchGoodResults(
                        minPatYoY, minRevenueYoY, minPatQoQ, minRevenueQoQ,
                        minPbtYoY, minPbtQoQ,
                        true, prevQ, prevFY,
                        resultType, 90, 500
                );
                goodResults = goodResultsMono.block();
            }

            if (goodResults == null || goodResults.getStocks() == null || goodResults.getStocks().isEmpty()) {
                log.info("No good results found matching criteria");
                return buildEmptyResponse(minRevenueYoY, minRevenueQoQ, minPatYoY, minPatQoQ,
                        minPbtYoY, minPbtQoQ, minMarketCap, maxMarketCap, minPctChange, currentQuarterOnly,
                        quarter, fiscalYear, resultType, 90);
            }

            log.info("Step 1: Found {} stocks with good results in {}ms",
                    goodResults.getStocks().size(), System.currentTimeMillis() - step1Start);

            // Step 2: Get tickers from results
            List<String> tickers = goodResults.getStocks().stream()
                    .map(ResultsServiceClient.QuarterlyResultDTO::getTicker)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            // Step 3: Find financial result announcements for these tickers
            long step3Start = System.currentTimeMillis();
            Map<String, Announcement> tickerToAnnouncement = findFinancialResultAnnouncements(tickers);
            log.info("Step 3: Found {} announcements for {} tickers in {}ms",
                    tickerToAnnouncement.size(), tickers.size(), System.currentTimeMillis() - step3Start);

            // Step 4: Fetch stock analytics (current price, 52W high/low, mcap)
            long step4Start = System.currentTimeMillis();
            var stockAnalyticsMono = reportingServiceClient.fetchStockAnalytics(tickers);
            var stockAnalytics = stockAnalyticsMono.block();
            if (stockAnalytics == null) {
                stockAnalytics = Collections.emptyMap();
            }
            log.info("Step 4: Fetched stock analytics for {} tickers in {}ms",
                    stockAnalytics.size(), System.currentTimeMillis() - step4Start);

            // Step 5: Batch pre-load historical prices from DB cache (PERFORMANCE OPTIMIZATION)
            long step5Start = System.currentTimeMillis();
            Map<String, Double> batchPriceMap = batchLoadAnnouncementPrices(tickerToAnnouncement);
            log.info("Step 5: Batch loaded {} prices from cache in {}ms",
                    batchPriceMap.size(), System.currentTimeMillis() - step5Start);

            // Step 6: Build PEAD stock DTOs
            List<PeadStockDTO> peadStocks = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();

            // Debug: Log all keys in stockAnalytics map
            log.debug("StockAnalytics map keys: {}", stockAnalytics.keySet());

            for (var result : goodResults.getStocks()) {
                String ticker = result.getTicker();
                if (ticker == null) continue;

                // Get announcement for this ticker
                Announcement announcement = tickerToAnnouncement.get(ticker.toUpperCase());

                // Get stock analytics - try multiple key formats
                String nseKey = "NSE:" + ticker.toUpperCase();
                var analytics = stockAnalytics.get(nseKey);
                if (analytics == null) {
                    // Try without prefix
                    analytics = stockAnalytics.get(ticker.toUpperCase());
                }
                if (analytics == null) {
                    // Try with lowercase
                    analytics = stockAnalytics.get(nseKey.toLowerCase());
                }

                // Debug log for first few tickers
                if (peadStocks.size() < 5) {
                    log.info("Ticker {} -> analytics found: {}, pctFrom52WeekHigh: {}, rsRating: {}, rank1Week: {}",
                            ticker,
                            analytics != null,
                            analytics != null ? analytics.getPctFrom52WeekHigh() : null,
                            analytics != null ? analytics.getRsRating() : null,
                            analytics != null ? analytics.getRank1Week() : null);
                }

                // Build PEAD DTO - pass the pre-loaded price map for fast lookup
                PeadStockDTO dto = buildPeadStock(result, announcement, analytics, now, batchPriceMap);

                // Apply market cap filter
                if (minMarketCap != null && dto.getMarketCap() != null && dto.getMarketCap() < minMarketCap) {
                    continue;
                }
                if (maxMarketCap != null && dto.getMarketCap() != null && dto.getMarketCap() > maxMarketCap) {
                    continue;
                }

                // Apply min % change filter
                if (minPctChange != null && dto.getPctChangeSinceResults() != null
                        && dto.getPctChangeSinceResults() < minPctChange) {
                    continue;
                }

                peadStocks.add(dto);
            }

            log.info("Step 6: Built {} PEAD stocks after filters", peadStocks.size());

            // Step 7: Sort results
            sortPeadStocks(peadStocks, sortBy, sortOrder);

            // Step 7: Limit results
            if (peadStocks.size() > limit) {
                peadStocks = peadStocks.subList(0, limit);
            }

            // Step 8: Build response with summary
            log.info("PEAD scan completed in {}ms total", System.currentTimeMillis() - scanStart);
            return buildResponse(peadStocks, minRevenueYoY, minRevenueQoQ, minPatYoY, minPatQoQ,
                    minPbtYoY, minPbtQoQ, minMarketCap, maxMarketCap, minPctChange, currentQuarterOnly,
                    quarter, fiscalYear, resultType, sortBy, sortOrder, 90);

        } catch (Exception e) {
            log.error("Error running PEAD scanner: {}", e.getMessage(), e);
            return buildEmptyResponse(minRevenueYoY, minRevenueQoQ, minPatYoY, minPatQoQ,
                    minPbtYoY, minPbtQoQ, minMarketCap, maxMarketCap, minPctChange, currentQuarterOnly,
                    quarter, fiscalYear, resultType, 90);
        }
    }

    /**
     * Batch load historical prices from DB cache for all announcements.
     * This is the key performance optimization - loads all prices in ONE query
     * instead of N sequential queries.
     *
     * @param tickerToAnnouncement Map of ticker -> announcement
     * @return Map of "TICKER_DATE" -> price (e.g., "RELIANCE_2026-02-01" -> 1234.56)
     */
    private Map<String, Double> batchLoadAnnouncementPrices(Map<String, Announcement> tickerToAnnouncement) {
        Map<String, Double> result = new HashMap<>();

        if (tickerToAnnouncement.isEmpty()) {
            return result;
        }

        // Collect all ticker+date pairs we need
        List<String> tickersNeeded = new ArrayList<>();
        List<LocalDate> datesNeeded = new ArrayList<>();
        Map<String, LocalDate> tickerToDate = new HashMap<>();

        LocalDate today = LocalDate.now();

        for (var entry : tickerToAnnouncement.entrySet()) {
            String ticker = entry.getKey().toUpperCase();
            Announcement ann = entry.getValue();

            if (ann != null && ann.getAnnouncementDate() != null) {
                LocalDate announcementDate = ann.getAnnouncementDate().toLocalDate();

                // Skip if announcement is today or in future (no drift yet)
                if (!announcementDate.isBefore(today)) {
                    result.put(buildPriceKey(ticker, announcementDate), 0.0); // 0% change
                    continue;
                }

                LocalDate tradingDay = getLastTradingDay(announcementDate);
                LocalDate lastTradingDay = getLastTradingDay(today);

                // If market hasn't opened since announcement, 0% change
                if (!lastTradingDay.isAfter(tradingDay)) {
                    result.put(buildPriceKey(ticker, announcementDate), 0.0);
                    continue;
                }

                tickersNeeded.add(ticker);
                if (!datesNeeded.contains(tradingDay)) {
                    datesNeeded.add(tradingDay);
                }
                tickerToDate.put(ticker, tradingDay);
            }
        }

        if (tickersNeeded.isEmpty()) {
            return result;
        }

        // Batch fetch from DB cache - ONE query for all prices!
        List<AnnouncementPriceCache> cachedPrices = priceCacheRepository
                .findByTickersAndDates(tickersNeeded, datesNeeded);

        log.debug("Batch query returned {} cached prices for {} tickers", cachedPrices.size(), tickersNeeded.size());

        // Build lookup map from cached results
        Map<String, AnnouncementPriceCache> cacheMap = new HashMap<>();
        for (AnnouncementPriceCache cache : cachedPrices) {
            String key = buildPriceKey(cache.getTicker(), cache.getPriceDate());
            cacheMap.put(key, cache);
        }

        // Track which prices are missing from cache (need Yahoo fetch)
        List<String> missingTickers = new ArrayList<>();
        List<LocalDate> missingDates = new ArrayList<>();

        for (String ticker : tickersNeeded) {
            LocalDate date = tickerToDate.get(ticker);
            if (date == null) continue;

            String key = buildPriceKey(ticker, date);
            AnnouncementPriceCache cached = cacheMap.get(key);

            if (cached != null) {
                if (cached.getFetchStatus() == AnnouncementPriceCache.FetchStatus.SUCCESS && cached.getClosePrice() != null) {
                    result.put(key, cached.getClosePrice().doubleValue());
                } else {
                    // Previously failed - mark as null (will use fallback)
                    result.put(key, null);
                }
            } else {
                // Cache miss - need to fetch
                missingTickers.add(ticker);
                missingDates.add(date);
            }
        }

        // Fetch missing prices in parallel (if any)
        if (!missingTickers.isEmpty()) {
            log.info("Cache miss for {} tickers - fetching from Yahoo in parallel", missingTickers.size());
            Map<String, Double> fetched = fetchMissingPricesInParallel(missingTickers, missingDates);
            result.putAll(fetched);
        }

        return result;
    }

    /**
     * Build a cache key for ticker+date combination.
     */
    private String buildPriceKey(String ticker, LocalDate date) {
        return ticker.toUpperCase() + "_" + date.toString();
    }

    /**
     * Fetch missing prices from Yahoo Finance in parallel.
     * Uses a thread pool for concurrent API calls.
     */
    private Map<String, Double> fetchMissingPricesInParallel(List<String> tickers, List<LocalDate> dates) {
        Map<String, Double> result = new ConcurrentHashMap<>();

        if (tickers.size() != dates.size()) {
            log.error("Ticker/date list size mismatch!");
            return result;
        }

        // Use a fixed thread pool for parallel fetching (limit concurrent Yahoo calls)
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(10, tickers.size()));
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < tickers.size(); i++) {
            final String ticker = tickers.get(i);
            final LocalDate date = dates.get(i);

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    Double price = fetchFromYahooFinance(ticker, date);
                    String key = buildPriceKey(ticker, date);
                    result.put(key, price);

                    // Save to DB cache for next time
                    savePriceToDbCache(ticker, date, price);
                } catch (Exception e) {
                    log.debug("Failed to fetch price for {} on {}: {}", ticker, date, e.getMessage());
                }
            }, executor);

            futures.add(future);
        }

        // Wait for all fetches to complete (with timeout)
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Some price fetches timed out or failed: {}", e.getMessage());
        } finally {
            executor.shutdown();
        }

        return result;
    }

    /**
     * Find the latest financial result announcement for each ticker.
     * OPTIMIZED: Uses ONE batch query instead of N individual queries.
     */
    private Map<String, Announcement> findFinancialResultAnnouncements(List<String> tickers) {
        Map<String, Announcement> result = new HashMap<>();

        if (tickers.isEmpty()) {
            return result;
        }

        LocalDateTime afterDate = LocalDateTime.now().minusDays(90);

        // Convert all tickers to uppercase
        List<String> upperTickers = tickers.stream()
                .map(String::toUpperCase)
                .distinct()
                .collect(Collectors.toList());

        // BATCH QUERY: Fetch ALL announcements for ALL tickers in ONE query
        List<Announcement> allAnnouncements = announcementRepository
                .findByNseTickersInAndAfterDate(upperTickers, afterDate);

        log.debug("Batch fetched {} announcements for {} tickers", allAnnouncements.size(), upperTickers.size());

        // Group by nseTicker and find the latest financial result for each
        Map<String, List<Announcement>> announcementsByTicker = allAnnouncements.stream()
                .filter(a -> a.getNseTicker() != null)
                .collect(Collectors.groupingBy(a -> a.getNseTicker().toUpperCase()));

        // Track which tickers we found
        Set<String> foundTickers = new HashSet<>();

        for (String ticker : upperTickers) {
            List<Announcement> tickerAnnouncements = announcementsByTicker.get(ticker);

            if (tickerAnnouncements != null && !tickerAnnouncements.isEmpty()) {
                // Find latest financial result for this ticker
                Optional<Announcement> financialResult = tickerAnnouncements.stream()
                        .filter(this::isFinancialResultAnnouncement)
                        .max(Comparator.comparing(Announcement::getAnnouncementDate));

                if (financialResult.isPresent()) {
                    result.put(ticker, financialResult.get());
                    foundTickers.add(ticker);
                } else {
                    // Fallback: use "Board Meeting Intimation" for financial results
                    // when the actual outcome announcement is missing from DB
                    Optional<Announcement> intimation = tickerAnnouncements.stream()
                            .filter(this::isBoardMeetingIntimationForResults)
                            .max(Comparator.comparing(Announcement::getAnnouncementDate));
                    if (intimation.isPresent()) {
                        result.put(ticker, intimation.get());
                        foundTickers.add(ticker);
                        log.info("Using board meeting intimation as fallback for ticker {}", ticker);
                    }
                }
            }
        }

        // For tickers not found via exact match, try company name fallback
        // (This is rare, only for abbreviated tickers like "ARIS" vs "ARISINFRA")
        List<String> missingTickers = upperTickers.stream()
                .filter(t -> !foundTickers.contains(t))
                .collect(Collectors.toList());

        if (!missingTickers.isEmpty() && missingTickers.size() <= 20) {
            // Only do fallback for a small number of missing tickers
            log.debug("Trying company name fallback for {} missing tickers", missingTickers.size());
            for (String ticker : missingTickers) {
                List<Announcement> companyNameResults = announcementRepository
                        .findByCompanyNameContainingAndAfterDate(ticker, afterDate);

                Optional<Announcement> financialResult = companyNameResults.stream()
                        .filter(this::isFinancialResultAnnouncement)
                        .max(Comparator.comparing(Announcement::getAnnouncementDate));

                if (financialResult.isPresent()) {
                    result.put(ticker, financialResult.get());
                    log.debug("Found announcement for {} via company name search: {}",
                            ticker, financialResult.get().getCompanyName());
                }
            }
        }

        return result;
    }

    /**
     * Check if an announcement is a financial result based on subject/category.
     */
    private boolean isFinancialResultAnnouncement(Announcement announcement) {
        String subject = announcement.getSubject() != null
                ? announcement.getSubject().toLowerCase() : "";
        String category = announcement.getCategory() != null
                ? announcement.getCategory().toLowerCase() : "";

        // Direct category match - "Result" category is always a financial result
        if (category.equals("result")) {
            return true;
        }

        // Exclude board meeting intimations — these are future-looking notices
        // ("will consider financial results"), not actual results
        if (subject.contains("board meeting intimation") || subject.contains("intimation of board meeting")) {
            return false;
        }

        for (String keyword : FINANCIAL_RESULT_KEYWORDS) {
            if (subject.contains(keyword) || category.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if an announcement is a "Board Meeting Intimation" that mentions financial results.
     * Used as a fallback when the actual result outcome announcement is missing from DB.
     */
    private boolean isBoardMeetingIntimationForResults(Announcement announcement) {
        String subject = announcement.getSubject() != null
                ? announcement.getSubject().toLowerCase() : "";
        String category = announcement.getCategory() != null
                ? announcement.getCategory().toLowerCase() : "";

        // Must be a board meeting intimation
        if (!subject.contains("board meeting intimation") && !subject.contains("intimation of board meeting")
                && !category.equals("board meeting")) {
            return false;
        }

        // Must mention financial results
        return subject.contains("financial result") || subject.contains("audited")
                || subject.contains("unaudited") || subject.contains("un-audited");
    }

    /**
     * Build a PEAD stock DTO from results, announcement, and analytics data.
     * Uses pre-loaded price map for fast % change calculation.
     *
     * @param result Quarterly results from Python service
     * @param announcement Financial result announcement
     * @param analytics Stock analytics (CMP, 52W, etc.)
     * @param now Current timestamp
     * @param batchPriceMap Pre-loaded historical prices map (ticker_date -> price)
     */
    private PeadStockDTO buildPeadStock(
            ResultsServiceClient.QuarterlyResultDTO result,
            Announcement announcement,
            ReportingServiceClient.StockAnalyticsDTO analytics,
            LocalDateTime now,
            Map<String, Double> batchPriceMap) {

        PeadStockDTO.PeadStockDTOBuilder builder = PeadStockDTO.builder()
                .ticker(result.getTicker())
                .hasResultsData(true);

        // Quarterly results data
        builder.quarter(result.getQuarter())
                .fiscalYear(result.getFiscalYear())
                .quarterLabel(result.getQuarterLabel())
                .resultType(result.getResultType() != null ? result.getResultType() : "consolidated")
                .revenue(result.getRevenue())
                .pat(result.getPat())
                .pbt(result.getPbt())
                .ebitda(result.getEbitda())
                .revenueYoY(result.getRevenueYoY())
                .revenueQoQ(result.getRevenueQoQ())
                .patYoY(result.getPatYoY())
                .patQoQ(result.getPatQoQ())
                .pbtYoY(result.getPbtYoY())
                .pbtQoQ(result.getPbtQoQ())
                .patMargin(result.getPatMargin())
                .ebitdaMargin(result.getEbitdaMargin());

        // Announcement data
        if (announcement != null) {
            builder.announcementId(announcement.getId())
                    .newsId(announcement.getNewsId())
                    .announcementDate(announcement.getAnnouncementDate())
                    .subject(announcement.getSubject())
                    .companyName(announcement.getCompanyName());

            // Calculate days since announcement
            int daysSince = (int) ChronoUnit.DAYS.between(announcement.getAnnouncementDate(), now);
            builder.daysSinceAnnouncement(daysSince);
        }

        // Stock analytics data
        if (analytics != null) {
            builder.hasStockData(true);

            Double currentPrice = analytics.getCmp() != null ? analytics.getCmp() : analytics.getPrice();
            builder.currentPrice(currentPrice)
                    .marketCap(analytics.getMarketCap())
                    .high52Week(analytics.getHigh52Week())
                    .low52Week(analytics.getLow52Week());

            // Use company name from analytics if not set from announcement
            if ((announcement == null || announcement.getCompanyName() == null) && analytics.getName() != null) {
                builder.companyName(analytics.getName());
            }

            // Use pre-calculated % from 52W high/low from reporting-service
            if (analytics.getPctFrom52WeekHigh() != null) {
                builder.pctFrom52WeekHigh(analytics.getPctFrom52WeekHigh());
            }
            if (analytics.getPctFrom52WeekLow() != null) {
                builder.pctFrom52WeekLow(analytics.getPctFrom52WeekLow());
            }

            // Use pre-calculated RS rating from reporting-service
            if (analytics.getRsRating() != null) {
                builder.rsRating(analytics.getRsRating());
            }

            // Calculate actual % change since announcement date using pre-loaded prices
            // This is now O(1) lookup instead of O(n) DB queries!
            if (announcement != null && announcement.getAnnouncementDate() != null && currentPrice != null) {
                LocalDate announcementDate = announcement.getAnnouncementDate().toLocalDate();
                Double actualPctChange = calculatePctChangeFast(
                        result.getTicker(), announcementDate, currentPrice, batchPriceMap);

                if (actualPctChange != null) {
                    builder.pctChangeSinceResults(actualPctChange);
                } else {
                    // Fallback to rank1Week if price lookup fails
                    if (analytics.getRank1Week() != null) {
                        builder.pctChangeSinceResults(analytics.getRank1Week());
                    } else if (analytics.getRank1Month() != null) {
                        builder.pctChangeSinceResults(analytics.getRank1Month());
                    }
                }
            } else {
                // No announcement date, use rank1Week as fallback
                if (analytics.getRank1Week() != null) {
                    builder.pctChangeSinceResults(analytics.getRank1Week());
                } else if (analytics.getRank1Month() != null) {
                    builder.pctChangeSinceResults(analytics.getRank1Month());
                }
            }
        } else {
            builder.hasStockData(false);
        }

        return builder.build();
    }

    /**
     * Fast % change calculation using pre-loaded price map.
     * O(1) lookup instead of DB query per stock.
     */
    private Double calculatePctChangeFast(String ticker, LocalDate announcementDate,
                                          Double currentPrice, Map<String, Double> priceMap) {
        if (ticker == null || announcementDate == null || currentPrice == null || currentPrice <= 0) {
            return null;
        }

        try {
            LocalDate today = LocalDate.now();

            // If announcement is today or in future, no drift yet
            if (!announcementDate.isBefore(today)) {
                return 0.0;
            }

            LocalDate tradingDay = getLastTradingDay(announcementDate);
            LocalDate lastTradingDay = getLastTradingDay(today);

            // If market hasn't opened since announcement, no drift yet
            if (!lastTradingDay.isAfter(tradingDay)) {
                return 0.0;
            }

            // Look up price from pre-loaded map - O(1)!
            String key = buildPriceKey(ticker.toUpperCase(), tradingDay);
            Double priceOnDate = priceMap.get(key);

            // Special case: 0.0 means "no drift" (weekend/future announcement)
            if (priceOnDate != null && priceOnDate == 0.0) {
                return 0.0;
            }

            if (priceOnDate == null || priceOnDate <= 0) {
                log.debug("No cached price for {} on {} - falling back", ticker, tradingDay);
                return null; // Will use rank1Week fallback
            }

            // Calculate percentage change
            double pctChange = ((currentPrice - priceOnDate) / priceOnDate) * 100;
            return Math.round(pctChange * 100.0) / 100.0; // Round to 2 decimal places

        } catch (Exception e) {
            log.warn("Error calculating fast price change for {}: {}", ticker, e.getMessage());
            return null;
        }
    }

    /**
     * Sort PEAD stocks by the specified field.
     */
    private void sortPeadStocks(List<PeadStockDTO> stocks, String sortBy, String sortOrder) {
        Comparator<PeadStockDTO> comparator;

        switch (sortBy != null ? sortBy.toLowerCase() : "pctchangesinceresults") {
            case "patYoy":
            case "patyoy":
                comparator = Comparator.comparing(
                        PeadStockDTO::getPatYoY,
                        Comparator.nullsLast(Comparator.naturalOrder())
                );
                break;
            case "revenueYoy":
            case "revenueyoy":
                comparator = Comparator.comparing(
                        PeadStockDTO::getRevenueYoY,
                        Comparator.nullsLast(Comparator.naturalOrder())
                );
                break;
            case "announcementDate":
            case "announcementdate":
                comparator = Comparator.comparing(
                        PeadStockDTO::getAnnouncementDate,
                        Comparator.nullsLast(Comparator.naturalOrder())
                );
                break;
            case "marketCap":
            case "marketcap":
                comparator = Comparator.comparing(
                        PeadStockDTO::getMarketCap,
                        Comparator.nullsLast(Comparator.naturalOrder())
                );
                break;
            case "pctFrom52WeekHigh":
            case "pctfrom52weekhigh":
                comparator = Comparator.comparing(
                        PeadStockDTO::getPctFrom52WeekHigh,
                        Comparator.nullsLast(Comparator.naturalOrder())
                );
                break;
            case "rsRating":
            case "rsrating":
                comparator = Comparator.comparing(
                        PeadStockDTO::getRsRating,
                        Comparator.nullsLast(Comparator.naturalOrder())
                );
                break;
            case "pbtYoY":
            case "pbtyoy":
                comparator = Comparator.comparing(
                        PeadStockDTO::getPbtYoY,
                        Comparator.nullsLast(Comparator.naturalOrder())
                );
                break;
            case "pbtQoQ":
            case "pbtqoq":
                comparator = Comparator.comparing(
                        PeadStockDTO::getPbtQoQ,
                        Comparator.nullsLast(Comparator.naturalOrder())
                );
                break;
            case "pctChangeSinceResults":
            case "pctchangesinceresults":
            default:
                comparator = Comparator.comparing(
                        PeadStockDTO::getPctChangeSinceResults,
                        Comparator.nullsLast(Comparator.naturalOrder())
                );
                break;
        }

        // Apply sort order
        if ("asc".equalsIgnoreCase(sortOrder)) {
            stocks.sort(comparator);
        } else {
            stocks.sort(comparator.reversed());
        }
    }

    /**
     * Build the full response with summary statistics.
     */
    private PeadScannerResponse buildResponse(
            List<PeadStockDTO> stocks,
            Double minRevenueYoY, Double minRevenueQoQ,
            Double minPatYoY, Double minPatQoQ,
            Double minPbtYoY, Double minPbtQoQ,
            Double minMarketCap, Double maxMarketCap,
            Double minPctChange, boolean currentQuarterOnly,
            String quarter, Integer fiscalYear, String resultType,
            String sortBy, String sortOrder, int days) {

        // Build summary
        PeadScannerResponse.PeadSummary summary = buildSummary(stocks);

        // Build filters info
        PeadScannerResponse.PeadFilters filters = PeadScannerResponse.PeadFilters.builder()
                .minRevenueYoY(minRevenueYoY)
                .minRevenueQoQ(minRevenueQoQ)
                .minPatYoY(minPatYoY)
                .minPatQoQ(minPatQoQ)
                .minPbtYoY(minPbtYoY)
                .minPbtQoQ(minPbtQoQ)
                .minMarketCap(minMarketCap)
                .maxMarketCap(maxMarketCap)
                .minPctChangeSinceResults(minPctChange)
                .currentQuarterOnly(currentQuarterOnly)
                .quarter(quarter)
                .fiscalYear(fiscalYear)
                .resultType(resultType)
                .days(days)
                .build();

        return PeadScannerResponse.builder()
                .stocks(stocks)
                .count(stocks.size())
                .totalAnnouncements(stocks.size())
                .filters(filters)
                .summary(summary)
                .generatedAt(LocalDateTime.now())
                .sortedBy(sortBy != null ? sortBy : "pctChangeSinceResults")
                .sortOrder(sortOrder != null ? sortOrder : "desc")
                .build();
    }

    /**
     * Build summary statistics for the scan results.
     */
    private PeadScannerResponse.PeadSummary buildSummary(List<PeadStockDTO> stocks) {
        if (stocks.isEmpty()) {
            return PeadScannerResponse.PeadSummary.builder()
                    .totalStocks(0)
                    .positiveGainers(0)
                    .negativeGainers(0)
                    .build();
        }

        int positive = 0;
        int negative = 0;
        double sum = 0;
        Double max = null;
        Double min = null;
        String topGainer = null;
        String topLoser = null;

        for (PeadStockDTO stock : stocks) {
            Double pct = stock.getPctChangeSinceResults();
            if (pct != null) {
                if (pct >= 0) positive++;
                else negative++;
                sum += pct;

                if (max == null || pct > max) {
                    max = pct;
                    topGainer = stock.getTicker();
                }
                if (min == null || pct < min) {
                    min = pct;
                    topLoser = stock.getTicker();
                }
            }
        }

        double avg = stocks.isEmpty() ? 0 : sum / stocks.size();

        return PeadScannerResponse.PeadSummary.builder()
                .totalStocks(stocks.size())
                .positiveGainers(positive)
                .negativeGainers(negative)
                .avgPctChange(Math.round(avg * 100.0) / 100.0)
                .maxPctChange(max != null ? Math.round(max * 100.0) / 100.0 : null)
                .minPctChange(min != null ? Math.round(min * 100.0) / 100.0 : null)
                .topGainer(topGainer)
                .topLoser(topLoser)
                .build();
    }

    /**
     * Build an empty response when no data is found.
     */
    private PeadScannerResponse buildEmptyResponse(
            Double minRevenueYoY, Double minRevenueQoQ,
            Double minPatYoY, Double minPatQoQ,
            Double minPbtYoY, Double minPbtQoQ,
            Double minMarketCap, Double maxMarketCap,
            Double minPctChange, boolean currentQuarterOnly,
            String quarter, Integer fiscalYear, String resultType, int days) {

        PeadScannerResponse.PeadFilters filters = PeadScannerResponse.PeadFilters.builder()
                .minRevenueYoY(minRevenueYoY)
                .minRevenueQoQ(minRevenueQoQ)
                .minPatYoY(minPatYoY)
                .minPatQoQ(minPatQoQ)
                .minPbtYoY(minPbtYoY)
                .minPbtQoQ(minPbtQoQ)
                .minMarketCap(minMarketCap)
                .maxMarketCap(maxMarketCap)
                .minPctChangeSinceResults(minPctChange)
                .currentQuarterOnly(currentQuarterOnly)
                .quarter(quarter)
                .fiscalYear(fiscalYear)
                .resultType(resultType)
                .days(days)
                .build();

        return PeadScannerResponse.builder()
                .stocks(Collections.emptyList())
                .count(0)
                .totalAnnouncements(0)
                .filters(filters)
                .summary(PeadScannerResponse.PeadSummary.builder()
                        .totalStocks(0)
                        .positiveGainers(0)
                        .negativeGainers(0)
                        .build())
                .generatedAt(LocalDateTime.now())
                .sortedBy("pctChangeSinceResults")
                .sortOrder("desc")
                .build();
    }

    /**
     * Calculate actual price change % since announcement date.
     * Uses Yahoo Finance to get historical closing price.
     *
     * For weekend/holiday announcements: uses last trading day's close as baseline.
     * If market hasn't opened since announcement, returns 0% (no drift yet).
     *
     * @param ticker Stock ticker (e.g., "RELIANCE")
     * @param announcementDate The date of the announcement
     * @param currentPrice Current market price
     * @return Percentage change since announcement date, or null if couldn't calculate
     */
    private Double calculatePctChangeSinceAnnouncement(String ticker, LocalDate announcementDate, Double currentPrice) {
        if (ticker == null || announcementDate == null || currentPrice == null || currentPrice <= 0) {
            return null;
        }

        try {
            LocalDate today = LocalDate.now();

            // If announcement is today or in future, no drift yet
            if (!announcementDate.isBefore(today)) {
                log.debug("Announcement date {} is today or future for {} - returning 0%", announcementDate, ticker);
                return 0.0;
            }

            // Check if market has opened since announcement
            // If announcement was on weekend and today is still weekend, return 0
            LocalDate lastTradingDay = getLastTradingDay(today);
            LocalDate announcementTradingDay = getLastTradingDay(announcementDate);

            // If the last trading day before/on announcement is same as last trading day before today,
            // market hasn't opened since announcement - no drift yet
            if (!lastTradingDay.isAfter(announcementTradingDay)) {
                log.debug("Market hasn't opened since announcement {} for {} - returning 0%", announcementDate, ticker);
                return 0.0;
            }

            // Get price on the last trading day before/on announcement
            Double priceOnDate = fetchHistoricalPrice(ticker, announcementTradingDay);
            if (priceOnDate == null || priceOnDate <= 0) {
                log.debug("Could not fetch historical price for {} on {}", ticker, announcementTradingDay);
                return null;
            }

            // Calculate percentage change
            double pctChange = ((currentPrice - priceOnDate) / priceOnDate) * 100;
            log.debug("Price change for {}: {} ({}) -> {} = {}%", ticker, priceOnDate, announcementTradingDay, currentPrice, pctChange);
            return Math.round(pctChange * 100.0) / 100.0; // Round to 2 decimal places

        } catch (Exception e) {
            log.warn("Error calculating price change for {}: {}", ticker, e.getMessage());
            return null;
        }
    }

    /**
     * Get the last trading day on or before the given date.
     * Skips weekends (Saturday/Sunday). Does not account for market holidays.
     */
    private LocalDate getLastTradingDay(LocalDate date) {
        java.time.DayOfWeek dow = date.getDayOfWeek();
        if (dow == java.time.DayOfWeek.SUNDAY) {
            return date.minusDays(2); // Go back to Friday
        } else if (dow == java.time.DayOfWeek.SATURDAY) {
            return date.minusDays(1); // Go back to Friday
        }
        return date; // Weekday - assume it's a trading day
    }

    /**
     * Fetch historical closing price for a ticker on a specific date.
     * Uses DB cache first, then falls back to Yahoo Finance API.
     */
    private Double fetchHistoricalPrice(String ticker, LocalDate targetDate) {
        String normalizedTicker = ticker.toUpperCase();
        if (normalizedTicker.startsWith("NSE:")) {
            normalizedTicker = normalizedTicker.substring(4);
        }

        // 1. Check DB cache first
        Optional<AnnouncementPriceCache> cached = priceCacheRepository
                .findByTickerAndPriceDate(normalizedTicker, targetDate);

        if (cached.isPresent()) {
            AnnouncementPriceCache cacheEntry = cached.get();
            if (cacheEntry.getFetchStatus() == AnnouncementPriceCache.FetchStatus.SUCCESS) {
                log.debug("Cache HIT for {} on {}: {}", normalizedTicker, targetDate, cacheEntry.getClosePrice());
                return cacheEntry.getClosePrice() != null ? cacheEntry.getClosePrice().doubleValue() : null;
            } else {
                // Previously failed fetch - return null without retrying
                log.debug("Cache HIT (NOT_FOUND) for {} on {}", normalizedTicker, targetDate);
                return null;
            }
        }

        // 2. Check in-memory cache (for prices fetched in this session)
        Map<LocalDate, Double> tickerCache = priceCache.get(normalizedTicker);
        if (tickerCache != null && tickerCache.containsKey(targetDate)) {
            Double price = tickerCache.get(targetDate);
            // Save to DB cache for persistence
            savePriceToDbCache(normalizedTicker, targetDate, price);
            return price;
        }

        // 3. Cache miss - fetch from Yahoo Finance
        log.debug("Cache MISS for {} on {} - fetching from Yahoo", normalizedTicker, targetDate);
        Double price = fetchFromYahooFinance(normalizedTicker, targetDate);

        // 4. Save to DB cache (even if null/failed)
        savePriceToDbCache(normalizedTicker, targetDate, price);

        return price;
    }

    /**
     * Save price to DB cache for persistence across restarts.
     */
    private void savePriceToDbCache(String ticker, LocalDate date, Double price) {
        try {
            AnnouncementPriceCache cacheEntry = AnnouncementPriceCache.builder()
                    .ticker(ticker)
                    .priceDate(date)
                    .closePrice(price != null ? BigDecimal.valueOf(price) : null)
                    .fetchStatus(price != null ?
                            AnnouncementPriceCache.FetchStatus.SUCCESS :
                            AnnouncementPriceCache.FetchStatus.NOT_FOUND)
                    .build();
            priceCacheRepository.save(cacheEntry);
            log.debug("Saved to DB cache: {} on {} = {}", ticker, date, price);
        } catch (Exception e) {
            // Ignore duplicate key errors (race condition)
            log.debug("Could not save to cache (possibly duplicate): {}", e.getMessage());
        }
    }

    /**
     * Fetch historical price from Yahoo Finance API.
     */
    private Double fetchFromYahooFinance(String ticker, LocalDate targetDate) {
        try {
            String yahooSymbol = ticker + ".NS";

            // Fetch data from announcement date to today
            long fromTimestamp = targetDate.atStartOfDay(ZoneId.of("Asia/Kolkata")).toEpochSecond();
            long toTimestamp = System.currentTimeMillis() / 1000;

            String apiUrl = "https://query1.finance.yahoo.com/v8/finance/chart/" + yahooSymbol
                    + "?period1=" + fromTimestamp
                    + "&period2=" + toTimestamp
                    + "&interval=1d";

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log.debug("HTTP {} for historical price {}", responseCode, yahooSymbol);
                return null;
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            JsonNode root = objectMapper.readTree(response.toString());
            JsonNode resultArray = root.path("chart").path("result");
            if (!resultArray.isArray() || resultArray.size() == 0) {
                return null;
            }

            JsonNode result = resultArray.get(0);
            JsonNode timestamps = result.path("timestamp");
            JsonNode closes = result.path("indicators").path("quote").get(0).path("close");

            if (!timestamps.isArray() || timestamps.size() == 0) {
                return null;
            }

            // Build in-memory cache for this ticker and find the closest date
            Map<LocalDate, Double> newCache = new HashMap<>();
            Double closestPrice = null;
            long closestDiff = Long.MAX_VALUE;

            for (int i = 0; i < timestamps.size(); i++) {
                if (closes.get(i).isNull()) continue;

                long ts = timestamps.get(i).asLong();
                double closePrice = closes.get(i).asDouble();
                LocalDate date = Instant.ofEpochSecond(ts)
                        .atZone(ZoneId.of("Asia/Kolkata"))
                        .toLocalDate();

                newCache.put(date, closePrice);

                // Find price closest to target date (on or after)
                long diff = ChronoUnit.DAYS.between(targetDate, date);
                if (diff >= 0 && diff < closestDiff) {
                    closestDiff = diff;
                    closestPrice = closePrice;
                }
            }

            // Update in-memory cache (still useful for session-level caching)
            priceCache.put(ticker, newCache);

            // Only return the price for target date (or closest)
            // DB cache is saved by the caller (fetchHistoricalPrice) - only for the announcement date
            if (newCache.containsKey(targetDate)) {
                return newCache.get(targetDate);
            }
            return closestPrice;

        } catch (Exception e) {
            log.debug("Error fetching historical price for {}: {}", ticker, e.getMessage());
            return null;
        }
    }
}
