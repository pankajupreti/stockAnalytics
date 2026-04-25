# Development Skills & Best Practices

## Performance Optimization Checklist

Before starting any feature development, consider these patterns:

### 1. Database Query Optimization

#### Avoid N+1 Queries
```java
// BAD: N queries in a loop
for (String ticker : tickers) {
    repository.findByTicker(ticker);  // 1 query per iteration = N queries
}

// GOOD: 1 batch query + in-memory processing
List<Entity> all = repository.findByTickerIn(tickers);  // 1 query
Map<String, Entity> map = all.stream()
    .collect(Collectors.toMap(Entity::getTicker, e -> e));
```

#### Use Batch Repository Methods
```java
// Repository interface
@Query("SELECT e FROM Entity e WHERE e.ticker IN :tickers AND e.date IN :dates")
List<Entity> findByTickersAndDates(@Param("tickers") List<String> tickers,
                                    @Param("dates") List<LocalDate> dates);
```

#### Pre-load Data Before Loops
```java
// Load all data upfront
Map<String, Data> dataMap = loadAllDataInBatch(keys);

// Then use O(1) lookups in loop
for (Item item : items) {
    Data data = dataMap.get(item.getKey());  // Instant lookup
}
```

### 2. Caching Strategy

#### DB Cache for Persistent Data
- Use for data that doesn't change (historical prices, past results)
- Store in database table with `ticker + date` composite key
- Check cache before external API calls

```java
Optional<CacheEntry> cached = cacheRepository.findByTickerAndDate(ticker, date);
if (cached.isPresent()) {
    return cached.get().getValue();  // Cache HIT
}
// Cache MISS - fetch and save
Value value = fetchFromExternalApi(ticker, date);
cacheRepository.save(new CacheEntry(ticker, date, value));
return value;
```

#### In-Memory Cache for Session Data
- Use `ConcurrentHashMap` for thread-safe session caching
- Good for data reused within single request

### 3. Parallel Processing

#### Use CompletableFuture for Independent Tasks
```java
ExecutorService executor = Executors.newFixedThreadPool(10);

List<CompletableFuture<Void>> futures = items.stream()
    .map(item -> CompletableFuture.runAsync(() -> processItem(item), executor))
    .toList();

// Wait for all with timeout
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
    .get(30, TimeUnit.SECONDS);

executor.shutdown();
```

#### Parallel HTTP Calls
- External API calls can run in parallel
- Limit thread pool size to avoid overwhelming APIs
- Add timeouts to prevent hanging

### 4. Timing & Debugging

#### Add Timing Logs to Identify Bottlenecks
```java
long startTime = System.currentTimeMillis();
// ... operation ...
log.info("Step X completed in {}ms", System.currentTimeMillis() - startTime);
```

#### Log Summary Stats
```java
log.info("Processed {} items: {} cached, {} fetched, {} failed",
    total, cached, fetched, failed);
```

---

## Code Patterns for This Project

### Service Architecture
```
gateway-service (8080)     → Frontend + routing
oauth (8081)               → Authentication
portfolio-service (8082)   → User portfolios
reporting-service (8083)   → Stock analytics, quotes
sheet-import-service (8084)→ Google Sheets import
announcement-service (8092)→ BSE announcements, PEAD scanner
results-service (8090)     → Python - Screener.in scraping
```

### Common Fixes

#### RabbitMQ TTL Mismatch
```bash
curl -X DELETE -u guest:guest "http://localhost:15672/api/queues/%2F/results.fetch.retry"
```

#### Missing Ticker Mappings
```
GET /api/admin/missing-mappings?days=30
POST /api/admin/auto-discover-mappings?days=30&limit=50
```

#### Pre-populate Price Cache
```bash
curl -X POST "http://localhost:8092/api/test/prefetch-prices?days=30"
```

---

## Financial Result Detection Keywords

Used in `isFinancialResultAnnouncement()`:
```java
// Direct category match
if (category.equals("result")) return true;

// Keyword matching
List<String> KEYWORDS = List.of(
    "financial result",
    "quarterly result",
    "un-audited financial",
    "unaudited financial",
    "audited financial",
    "standalone financial",
    "consolidated financial",
    "outcome of board meeting",
    "board meeting outcome",
    "results for the quarter"
);
```

---

## Indian Fiscal Year Quarter Mapping

```
Announcement Month → Quarter Being Announced
Jan-Feb           → Q3 (Oct-Dec)
Apr-May           → Q4 (Jan-Mar)
Jul-Aug           → Q1 (Apr-Jun)
Oct-Nov           → Q2 (Jul-Sep)
```

Fiscal Year: April to March (FY2026 = Apr 2025 - Mar 2026)

---

## Portfolio Analytics Formulas

### Normalized Value (accounts for capital additions)
```java
adjustedBase = firstWealth + (currentTotalBuys - firstTotalBuys)
normalizedValue = (currentWealth / adjustedBase) * 100
```

### Return Percentage
```java
returnPct = lastNormalizedValue - 100.0  // e.g., 95.84 - 100 = -4.16%
```

### Weighted Average Buy Price (when adding shares)
```java
newAvgPrice = (oldQty * oldPrice + newQty * newPrice) / (oldQty + newQty)
```

---

## Weekend/Holiday Handling

```java
private LocalDate getLastTradingDay(LocalDate date) {
    DayOfWeek dow = date.getDayOfWeek();
    if (dow == DayOfWeek.SUNDAY) return date.minusDays(2);
    if (dow == DayOfWeek.SATURDAY) return date.minusDays(1);
    return date;
}
```

For announcements on weekends, use Friday's close price as baseline.
If market hasn't opened since announcement, return 0% drift.

---

## Testing Endpoints

### PEAD Scanner
```
GET /api/pead/scan?currentQuarterOnly=true&minPatYoY=20
GET /api/pead/scan/quick
GET /api/pead/scan/momentum
```

### Announcements
```
GET /api/test/announcements?page=0&size=20
POST /api/test/sync?fromDate=2026-02-01&toDate=2026-02-13
GET /api/test/debug/announcement?search=kalyan
```

### Results Reprocessing
```
POST /api/test/results/reprocess-all?days=30
POST /api/test/results/reprocess (body: {"tickers": ["TCS", "RELIANCE"]})
```

### EOD Price / Anchor Move
```
POST /api/eod-prices/prefill?date=2026-02-01&force=true  # Prefill event date
POST /api/eod-prices/backfill-historical?fromDate=...&toDate=...
POST /api/eod-prices/cancel  # Cancel running operation
GET  /api/eod-prices/status  # Check progress
GET  /api/anchor-prices?date=2026-02-01  # Get all prices for a date
GET  /api/anchor-events  # Get preset event dates (Budget, RBI, etc.)
```

---

## EOD Price Fetching Pattern

### High-Throughput Yahoo Finance Fetching
```java
private static final int PARALLEL_THREADS = 50;  // High parallelism for speed
// No batch delay - Yahoo can handle concurrent requests

ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_THREADS);
List<CompletableFuture<Void>> futures = new ArrayList<>();

for (Stock stock : stocks) {
    futures.add(CompletableFuture.runAsync(() -> {
        Double price = fetchPriceFromYahoo(stock.getTicker(), date);
        if (price != null && price > 0) {
            prices.put(stock.getTicker(), price);
        }
        progress.incrementAndGet();
    }, executor));
}

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
    .get(15, TimeUnit.MINUTES);
executor.shutdown();
```

### Performance: ~5-10 minutes for 3000+ stocks (vs 60+ minutes with delays)

### Yahoo Finance API Pattern
```java
String apiUrl = "https://query1.finance.yahoo.com/v8/finance/chart/" + yahooSymbol
    + "?period1=" + fromTimestamp
    + "&period2=" + toTimestamp
    + "&interval=1d";
```

### Ticker Conversion
```java
private String toYahooSymbol(String ticker) {
    String clean = ticker.trim().toUpperCase();
    if (clean.startsWith("NSE:")) clean = clean.substring(4);
    if (clean.startsWith("BSE:")) clean = clean.substring(4);
    return clean + ".NS";  // NSE suffix for Yahoo
}
```
