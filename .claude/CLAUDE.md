# Claude Session Progress

## Portfolio Analytics - Normalized Value Fix (Feb 5, 2026)

### Problem
When adding a new stock (QPOWER), the portfolio analytics graph would spike artificially because the normalized value calculation didn't account for new capital additions properly.

### Root Causes Identified

1. **Missing BUY Transactions**: When creating a new Position, no corresponding Transaction record was created. The normalized value calculation depends on transaction history to track capital flow.

2. **Incorrect Normalized Value Formula**: The original formula used `totalInvested` from current positions (which changes when you sell) instead of total BUY transactions (which only increases when you add new capital).

3. **Old Snapshots Had Bad Data**: Historical snapshots had incorrect `total_invested` values, so couldn't rely on stored snapshot data.

### Fixes Applied

#### 1. PortfolioService.java - Auto-create BUY Transaction
When a new position is created, now also creates a BUY transaction:
```java
// Also create a BUY transaction for tracking capital flow
Transaction tx = Transaction.builder()
        .userSub(userSub)
        .ticker(ticker)
        .type(Transaction.TransactionType.BUY)
        .quantity(req.getQuantity())
        .price(req.getBuyPrice())
        .transactionDate(req.getBuyDate())
        .positionId(saved.getId())
        .notes(req.getNotes())
        .build();
transactionRepository.save(tx);
```

#### 2. TransactionRepository.java - New Query
Added method to get total invested from BUY transactions as of a date:
```java
@Query("SELECT COALESCE(SUM(t.price * t.quantity), 0) FROM Transaction t WHERE t.userSub = :userSub AND t.type = 'BUY' AND t.transactionDate <= :asOfDate")
java.math.BigDecimal getTotalInvestedAsOfDate(@Param("userSub") String userSub, @Param("asOfDate") java.time.LocalDate asOfDate);
```

#### 3. PortfolioReturnsService.java - Fixed Normalized Calculation
Changed the normalized value calculation to use transaction history:
```java
// Get total invested from TRANSACTIONS as of first date and today
LocalDate firstDate = firstSnapshot.get().getSnapshotDate();
BigDecimal firstTotalBuys = transactionRepository.getTotalInvestedAsOfDate(userSub, firstDate);
BigDecimal currentTotalBuys = transactionRepository.getTotalInvestedAsOfDate(userSub, today);

// Capital added = total buys today - total buys at first snapshot
BigDecimal capitalAdded = currentTotalBuys.subtract(firstTotalBuys);
if (capitalAdded.compareTo(BigDecimal.ZERO) < 0) {
    capitalAdded = BigDecimal.ZERO;
}
BigDecimal adjustedBase = firstWealth.add(capitalAdded);

normalizedValue = totalWealth.divide(adjustedBase, 4, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100));
```

#### 4. SecurityConfig.java - Admin Endpoints
Made `/api/portfolio/admin/**` public for maintenance operations.

### Manual Fix for Existing Positions
For positions added before this fix that don't have transactions, run:
```sql
INSERT INTO transactions (user_sub, ticker, type, quantity, price, transaction_date, created_at, position_id)
SELECT user_sub, ticker, 'BUY', quantity, buy_price, buy_date, NOW(), id
FROM positions
WHERE id NOT IN (SELECT DISTINCT position_id FROM transactions WHERE position_id IS NOT NULL);
```

### Key Insight
The normalized value formula:
- `adjustedBase = firstWealth + (currentTotalBuys - firstTotalBuys)`
- `normalizedValue = (currentWealth / adjustedBase) * 100`

This ensures that when you add new capital, the base increases proportionally, so the graph only reflects actual portfolio performance, not capital injections.

---

## Other Fixes in This Session

### RS Rating Calculation (RsRatingService.java)
- Made calculation more lenient by adding fallbacks:
  - Use `rank1Week` if `rank1Month` is null
  - Calculate 1-year performance from `cmp/cmp365` if `rank1Year` is null
  - Require only 1 metric minimum instead of 2

### Announcement Service Fixes
- Fixed port in announcements.html (8085 -> 8092)
- Fixed Lombok annotation processor in pom.xml
- Rewrote TickerMapping.java to fix NoClassDefFoundError

### RabbitMQ Queue Management
- Results queue consumer delay: 3 seconds between requests
- Delete retry queue if TTL mismatch: `curl -u guest:guest -X DELETE "http://localhost:15672/api/queues/%2F/results.fetch.retry"`

---

## PEAD Scanner - pctChangeSinceResults Fix (Feb 5, 2026)

### Problem
PEAD scanner was showing wrong % change since results. For example, QPOWER announced results yesterday and gave ~5% move, but the page was showing +31.9%.

### Root Cause
The code was using `rank1Week` (1-week % change) as a proxy for "% change since results":
```java
// OLD CODE - WRONG
if (analytics.getRank1Week() != null) {
    builder.pctChangeSinceResults(analytics.getRank1Week());
}
```
This is incorrect because `rank1Week` is the change over the last 7 days, NOT the change since the specific announcement date.

### Fix Applied
Modified `PeadScannerService.java` to calculate the actual price change since the announcement date using Yahoo Finance historical data:

1. **Added historical price fetching**: New method `fetchHistoricalPrice(ticker, date)` fetches the closing price on a specific date from Yahoo Finance.

2. **Added price change calculation**: New method `calculatePctChangeSinceAnnouncement(ticker, announcementDate, currentPrice)` calculates the actual % change.

3. **Updated `buildPeadStock`** to use the actual calculation:
```java
// NEW CODE - CORRECT
if (announcement != null && announcement.getAnnouncementDate() != null && currentPrice != null) {
    LocalDate announcementDate = announcement.getAnnouncementDate().toLocalDate();
    Double actualPctChange = calculatePctChangeSinceAnnouncement(
            result.getTicker(), announcementDate, currentPrice);

    if (actualPctChange != null) {
        builder.pctChangeSinceResults(actualPctChange);
    } else {
        // Fallback to rank1Week if Yahoo fetch fails
        ...
    }
}
```

4. **Added caching**: Historical prices are cached in `priceCache` to avoid redundant API calls.

### Key Insight
The formula: `pctChange = ((currentPrice - priceOnAnnouncementDate) / priceOnAnnouncementDate) * 100`

This gives the actual drift since the specific announcement date, not an arbitrary 1-week/1-month change.

---

## Session Updates (Feb 8-9, 2026)

### RabbitMQ Results Queue - Expected Quarter Verification

#### Problem
Python consumer was marking results fetch as "success" if ANY results existed from Screener, even if they were old cached data. For example, HBLENGINE Q3 results announced but Screener only had Q2 - consumer said "success" and didn't retry.

#### Fix Applied

1. **Java ResultsFetchEvent.java** - Added `expectedQuarter` field:
```java
/** Expected quarter from announcement (e.g., "Q3 FY2026") */
private String expectedQuarter;
```

2. **Java ResultsEventPublisher.java** - Calculate expected quarter from announcement date:
```java
private String calculateExpectedQuarter(LocalDateTime announcementDate) {
    // Jan-Mar → Q3, Apr-Jun → Q4, Jul-Sep → Q1, Oct-Dec → Q2
    // Returns "Q3 FY2026" format based on Indian fiscal year
}
```

3. **Python rabbitmq_consumer.py** - Verify expected quarter before marking success:
```python
if expected_quarter:
    if expected_normalized != latest_normalized:
        logger.warning(f"Expected {expected_quarter} but latest is {latest_quarter} - will retry")
        return False  # Trigger retry!
```

### RabbitMQ Retry Queue - Changed to 5 Hours

Updated retry delay from 15 minutes to 5 hours:
- **Java RabbitMQConfig.java**: `RETRY_DELAY_MS = 5 * 60 * 60 * 1000L`
- **Python rabbitmq_consumer.py**: `RETRY_DELAY_MS = 5 * 60 * 60 * 1000`

Retry timeline:
```
Attempt 1: Immediate
Attempt 2: +5 hours
Attempt 3: +10 hours
Attempt 4: +15 hours
Attempt 5: +20 hours
→ DLQ (if all fail)
```

**Important**: Delete old queue if TTL mismatch error occurs:
```bash
curl -X DELETE -u guest:guest "http://localhost:15672/api/queues/%2F/results.fetch.retry"
```

### Announcement Service - Direct Category Match Fix

#### Problem
Announcements with category="Result" but generic subject (e.g., "Meeting Updates") weren't matching keyword list and weren't being published to RabbitMQ.

#### Fix Applied
Added direct category match in 6 files:
```java
// Direct category match - "Result" category is always a financial result
if (category.equals("result")) {
    return true;
}
```

Files modified:
- `AnnouncementService.java` (2 methods)
- `AnnouncementSyncScheduler.java`
- `TestController.java`
- `ResultsReprocessController.java`
- `PeadScannerService.java`
- `ResultsService.java`

### PEAD Scanner - Weekend Announcement Fix

#### Problem
GOLDIAM announced results on Saturday, showing +23.4% change on Sunday when it should be 0% (market closed).

#### Fix Applied
Updated `calculatePctChangeSinceAnnouncement()` in `PeadScannerService.java`:

```java
// If market hasn't opened since announcement, return 0%
LocalDate lastTradingDay = getLastTradingDay(today);
LocalDate announcementTradingDay = getLastTradingDay(announcementDate);

if (!lastTradingDay.isAfter(announcementTradingDay)) {
    return 0.0;  // No drift yet
}
```

Added helper method:
```java
private LocalDate getLastTradingDay(LocalDate date) {
    DayOfWeek dow = date.getDayOfWeek();
    if (dow == DayOfWeek.SUNDAY) return date.minusDays(2);
    if (dow == DayOfWeek.SATURDAY) return date.minusDays(1);
    return date;
}
```

### Portfolio Page - Refresh Prices Button

Added refresh button to portfolio.html toolbar:
```html
<button id="btn-refresh" class="btn btn-outline"
        style="background:#e0f2fe;border-color:#7dd3fc;">
    🔄 Refresh Prices
</button>
```

JavaScript handler in portfolio.js:
```javascript
async function refreshPrices() {
    await loadPrices();
    renderTable(byId("filter").value);
}
```

### Reprocess All Results Endpoint

Added endpoint to reprocess all financial result announcements:
```
POST /api/test/results/reprocess-all?days=30
```

This finds all financial result announcements, groups by ticker, and republishes to RabbitMQ with `expectedQuarter`.

---

## PEAD Scanner Performance - Price Caching (Feb 11, 2026)

### Problem
PEAD scanner was taking 2-4 minutes to load because it was making sequential Yahoo Finance API calls for each stock to get historical prices on announcement dates.

### Solution
Implemented DB-based price caching:

1. **Cache Table**: `announcement_price_cache`
   - Key: `ticker` + `price_date`
   - Stores: `close_price`, `fetch_status` (SUCCESS/NOT_FOUND/ERROR)
   - Persistent across restarts

2. **Cache Flow**:
   ```
   fetchHistoricalPrice(ticker, date):
   1. Check DB cache → HIT? Return cached price
   2. Check in-memory cache → HIT? Save to DB, return
   3. MISS → Fetch from Yahoo, save to DB cache, return
   ```

3. **Pre-fetch on Announcement Save**:
   - When a financial result announcement is saved, async job pre-fetches the price
   - Uses `@Async` annotation for non-blocking execution

4. **Admin Endpoints**:
   - `POST /api/test/prefetch-prices?days=30` - Batch pre-fetch for existing announcements
   - `GET /api/test/price-cache-stats` - Check cache size

### Files Created/Modified
- `AnnouncementPriceCache.java` - Entity
- `AnnouncementPriceCacheRepository.java` - Repository
- `PricePrefetchService.java` - Async pre-fetch service
- `PeadScannerService.java` - Updated to use DB cache
- `AnnouncementPersistenceService.java` - Triggers pre-fetch on save
- `AnnouncementServiceApplication.java` - Added `@EnableAsync`
- `TestController.java` - Added admin endpoints

### Performance Improvement
| Scenario | Before | After |
|----------|--------|-------|
| First load (no cache) | 2-4 min | 2-4 min (one-time) |
| Subsequent loads | 2-4 min | **2-3 seconds** |
| With pre-fetch | 2-4 min | **2-3 seconds** |

### To Populate Cache for Existing Data
```bash
curl -X POST "http://localhost:8092/api/test/prefetch-prices?days=30"
```

---

## Claude Code CLI Troubleshooting

### Issue: API Error 400 with `prompt-caching-scope-2026-01-05`
Version v2.1.37 has a bug with invalid beta header.

### Fix
Downgrade to v2.1.4:
```cmd
npm uninstall -g @anthropic-ai/claude-code
npm install -g @anthropic-ai/claude-code@2.1.4
```

**Note**: v2.1.4 only has Opus 4.5, not 4.6. Wait for Anthropic to fix newer versions for Opus 4.6 support.

---

## PEAD Scanner Performance - Batch Query Optimization (Feb 13, 2026)

### Problem
PEAD scanner was still taking 6-10 seconds despite price caching. Timing analysis revealed:

| Step | Time | Issue |
|------|------|-------|
| Step 1: Python service | 1155ms | OK |
| **Step 3: Find announcements** | **9168ms** | **257 individual DB queries!** |
| Step 4: Stock analytics | 50ms | OK |
| Step 5: Load prices | 27ms | OK (already optimized) |

### Root Cause
`findFinancialResultAnnouncements()` was looping through each ticker and making individual DB queries:
```java
// OLD - SLOW (257 queries for 257 tickers)
for (String ticker : tickers) {
    announcementRepository.findByNseTickersInAndAfterDate(List.of(upperTicker), afterDate);
}
```

### Solution - Batch Queries

#### 1. Batch Load Announcements (Step 3)
Changed to ONE batch query for all tickers:
```java
// NEW - FAST (1 query for all tickers)
List<Announcement> allAnnouncements = announcementRepository
    .findByNseTickersInAndAfterDate(upperTickers, afterDate);

// Group by ticker in-memory
Map<String, List<Announcement>> announcementsByTicker = allAnnouncements.stream()
    .collect(Collectors.groupingBy(a -> a.getNseTicker().toUpperCase()));
```

#### 2. Batch Load Prices (Step 5) - Already Done
```java
// ONE query for all prices
List<AnnouncementPriceCache> cachedPrices = priceCacheRepository
    .findByTickersAndDates(tickersNeeded, datesNeeded);
```

#### 3. Parallel Yahoo Fetching for Cache Misses
```java
ExecutorService executor = Executors.newFixedThreadPool(10);
List<CompletableFuture<Void>> futures = missingTickers.stream()
    .map(t -> CompletableFuture.runAsync(() -> fetchAndCache(t, date), executor))
    .toList();
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
```

### Performance After Optimization

| Step | Before | After |
|------|--------|-------|
| Step 3: Find announcements | 9168ms | **~100-200ms** |
| Step 5: Load prices | 27ms | 27ms |
| **Total** | **10418ms** | **~1.5-2 seconds** |

### Key Files Modified
- `PeadScannerService.java`:
  - `findFinancialResultAnnouncements()` - Uses batch query
  - `batchLoadAnnouncementPrices()` - Batch loads prices from DB cache
  - `fetchMissingPricesInParallel()` - Parallel Yahoo fetching
  - `calculatePctChangeFast()` - O(1) map lookup instead of DB query
  - `buildPeadStock()` - Accepts pre-loaded price map

### Timing Logs Added
```java
log.info("Step 1: Found {} stocks in {}ms", count, time);
log.info("Step 3: Found {} announcements in {}ms", count, time);
log.info("Step 4: Fetched analytics in {}ms", time);
log.info("Step 5: Batch loaded {} prices in {}ms", count, time);
log.info("PEAD scan completed in {}ms total", totalTime);
```

### Key Insight
**Always use batch queries when processing lists of items.** The pattern:
```java
// BAD: N queries
for (item : items) {
    repository.findByX(item);
}

// GOOD: 1 query + in-memory grouping
List<Result> all = repository.findByXIn(items);
Map<Key, List<Result>> grouped = all.stream().collect(groupingBy(...));
```

---

## EOD Price Service & Anchor Move Feature (Feb 15, 2026)

### Feature Overview
Allows users to see stock price movement since significant dates (Budget, RBI Policy, etc.) via an "Anchor Date" dropdown in the dashboard.

### Architecture

```
┌─────────────────────┐     ┌──────────────────────┐     ┌─────────────────┐
│  sheet-import-svc   │     │   reporting-svc      │     │    Frontend     │
│  (port 8091)        │     │   (port 8082)        │     │                 │
├─────────────────────┤     ├──────────────────────┤     ├─────────────────┤
│ EodPriceService     │────▶│ AnchorMoveService    │────▶│ app.js          │
│ - Daily EOD job     │     │ - Read-only from DB  │     │ - Anchor dropdown│
│ - Prefill API       │     │ - L1 Caffeine cache  │     │ - Move column   │
│ - Backfill API      │     │ - /api/anchor-prices │     │                 │
└─────────────────────┘     └──────────────────────┘     └─────────────────┘
         │                            ▲
         │                            │
         ▼                            │
┌─────────────────────────────────────┴─────────────────┐
│              anchor_price_cache table                  │
│  (ticker, price_date, close_price, fetched_at)        │
└───────────────────────────────────────────────────────┘
```

### Key Components

#### 1. sheet-import-service (Data Population)

**EodPriceService.java**
- Fetches EOD prices from Yahoo Finance for ALL stocks
- **50 parallel threads** for fast processing (~5-10 min for 3000+ stocks)
- Saves to `anchor_price_cache` table

**EodPriceJob.java**
- Scheduled job: `@Scheduled(cron = "0 0 18 * * MON-FRI", zone = "Asia/Kolkata")`
- Runs daily at 6 PM IST after market close

**EodPriceController.java** - APIs:
```
POST /api/eod-prices/prefill?date=2026-02-01&force=true  # Prefill event date
POST /api/eod-prices/backfill-historical?fromDate=...&toDate=...  # Backfill range
POST /api/eod-prices/cancel  # Cancel running operation
GET  /api/eod-prices/status  # Check progress
```

#### 2. reporting-service (Data Reading)

**AnchorMoveService.java**
- READ-ONLY from database (no Yahoo calls)
- L1 Caffeine cache for hot dates
- Returns Map<ticker, price> for a date

**AnchorMoveController.java** - APIs:
```
GET  /api/anchor-prices?date=2026-02-01  # Get all prices for date
GET  /api/anchor-prices/exists?date=...   # Check if data exists
GET  /api/anchor-events                   # Get preset event dates
POST /api/anchor-prices/warm?date=...     # Warm cache
```

#### 3. Frontend (app.js)

- Anchor date dropdown with presets (Budget, RBI Policy, etc.)
- Custom date picker option
- "Move Since Anchor" column (hidden by default, shows when anchor selected)
- Client-side calculation: `anchorMove = ((cmp - anchorPrice) / anchorPrice) * 100`
- Session cache for anchor prices

### Database Table

```sql
CREATE TABLE anchor_price_cache (
    ticker VARCHAR(50) NOT NULL,
    price_date DATE NOT NULL,
    close_price DOUBLE,
    fetched_at TIMESTAMP,
    PRIMARY KEY (ticker, price_date)
);
```

### Performance Optimization

**Initial Problem**: Sequential Yahoo API calls with cumulative delays = 60+ minutes

**Solution**:
- 50 parallel threads (up from 10)
- No artificial batch delays
- Completes 3000+ stocks in ~5-10 minutes

```java
private static final int PARALLEL_THREADS = 50;  // High parallelism
// No batch delay - Yahoo can handle concurrent requests
```

### Usage

1. **Prefill event dates** (one-time):
   ```bash
   curl -X POST "http://localhost:8091/api/eod-prices/prefill?date=2026-02-01&force=true"
   ```

2. **Check status**:
   ```bash
   curl "http://localhost:8091/api/eod-prices/status"
   ```

3. **Frontend**: Select anchor date from dropdown → "Move Since" column appears

---

## Anchor Date Auto-Prefill & Save Custom Events (Mar 29, 2026)

### Problem
When selecting a custom anchor date (e.g., March 31) on the dashboard, the "Move Since" column showed nothing because the EOD price data only exists for dates where the daily job has run. Users had no way to know data was missing or to trigger a fetch.

### Solution - Two Parts

#### Part 1: Auto-fetch missing price data

**Flow:**
```
User picks custom date in dropdown
        │
        ▼
Frontend: GET /reporting-service/api/anchor-prices/exists?date=YYYY-MM-DD
        │
        ├── Data exists (count > 100)?  → Show "Move Since" column immediately
        │
        └── No data? → POST /sheet-import-service/api/eod-prices/prefill-async?date=YYYY-MM-DD
                                │
                         Returns immediately, background thread fetches Yahoo prices
                         (50 parallel threads, ~3000 stocks)
                                │
                         Frontend polls GET /sheet-import-service/api/eod-prices/status
                         every 3 seconds, shows blue banner with progress:
                         "⏳ Loading prices for Mar 31... 1500/3098 stocks (48%)"
                                │
                         When done → green banner "✅ Price data loaded!"
                         Auto-refreshes dashboard, banner hides after 3 seconds
```

**Backend Changes:**

1. **EodPriceService.java** - New `fetchPricesForDateAsync()` method:
   - Wraps existing parallel fetch in `CompletableFuture.runAsync()`
   - Returns immediately with `{ status: "started", total: 3098 }`
   - Added `currentOperationDate` field for status tracking

2. **EodPriceController.java** - New endpoint:
   ```
   POST /api/eod-prices/prefill-async?date=2026-03-31  # Returns immediately, runs in background
   ```

3. **AnchorMoveController.java** - New endpoint:
   ```
   GET /api/anchor-prices/available-dates  # Returns list of dates with cached data
   ```

4. **AnchorMoveService.java** - New methods:
   - `getAvailableDates()` - returns all cached dates
   - `findNearestDate(date)` - finds nearest date on or before given date

5. **AnchorPriceCacheRepository.java** (reporting-service) - New query:
   ```java
   @Query("SELECT DISTINCT a.id.priceDate FROM AnchorPriceCache a WHERE a.id.priceDate <= :date ORDER BY a.id.priceDate DESC LIMIT 1")
   LocalDate findNearestDateOnOrBefore(@Param("date") LocalDate date);
   ```

#### Part 2: Save custom dates as named events

- When user picks a custom date, an inline name input + Save button appears
- Saved events stored in `localStorage` as `savedAnchorEvents` JSON array
- On page load, `loadSavedAnchorEvents()` injects saved events into dropdown:
  ```
  -- Select Event --
  Budget 2026 (Feb 1)        ← built-in presets
  RBI Policy (Feb 7)
  ── Saved Events ──         ← dynamically added
  FY End (Mar 31)            ← from localStorage
  Custom Date...
  Manage Saved...            ← delete saved events
  ```
- "Manage Saved..." opens a prompt to delete saved events

**Frontend Functions (app.js):**
- `activateAnchorDate(dateStr)` - checks data exists, triggers prefill if not
- `triggerAnchorPrefill(dateStr)` - calls async prefill endpoint
- `pollAnchorPrefillStatus(dateStr, total)` - polls every 3s, updates banner
- `showAnchorBanner(type, message)` / `hideAnchorBanner()` - progress/success/error banners
- `showSaveAnchorControls()` / `hideSaveAnchorControls()` - inline save UI
- `saveCustomAnchorDate()` - saves to localStorage, refreshes dropdown
- `getSavedAnchorEvents()` / `loadSavedAnchorEvents()` - localStorage read/write
- `showManageSavedEvents()` - prompt-based delete UI

### Files Modified
- `gateway-service/src/main/resources/static/app.js` - All frontend logic
- `sheet-import-service/.../service/EodPriceService.java` - Async prefill method
- `sheet-import-service/.../controller/EodPriceController.java` - Async endpoint
- `reporting-service/.../controller/AnchorMoveController.java` - Available dates endpoint
- `reporting-service/.../service/AnchorMoveService.java` - New query methods
- `reporting-service/.../repository/AnchorPriceCacheRepository.java` - Nearest date query

---

## PEAD Scanner Quarter Fallback Fix (Mar 29, 2026)

### Problem
On April 1 (start of new fiscal year), PEAD scanner showed "No stocks found" because it was looking for Q4 FY2026 results, but no Q4 results exist yet (quarter just ended).

### Fix
Added fallback logic: if `currentQuarterOnly=true` returns empty results, retry with previous quarter.

**PeadScannerService.java:**
```java
// If no results for current quarter, fall back to previous quarter
if (currentQuarterOnly && (goodResults == null || goodResults.getStocks().isEmpty())) {
    Map<String, Object> prevQtr = getPreviousQuarterInfo(curQ, curFY);
    // Retry with previous quarter (e.g., Q3 FY2026)
    goodResults = resultsServiceClient.fetchGoodResults(..., prevQ, prevFY, ...).block();
}
```

**Python main.py** - Fixed parameter priority:
```python
# BEFORE: current_quarter_only took priority even when explicit quarter was passed
# AFTER: explicit quarter/fiscal_year takes priority
if quarter and fiscal_year:
    target_quarter = quarter.upper()
    target_fiscal_year = fiscal_year
elif current_quarter_only:
    target_quarter, target_fiscal_year = get_current_quarter_info()
```

### Files Modified
- `announcement-service/.../service/PeadScannerService.java` - Quarter fallback + `getPreviousQuarterInfo()`
- `results-service-python/app/main.py` - Parameter priority fix

---

## Start Scripts Fix (Mar 29, 2026)

### Problem
Batch scripts to start all services without IntelliJ didn't work due to:
- Missing Maven wrappers in 5 services
- `cmd /c` nesting issues with PowerShell-hybrid mvnw.cmd
- PowerShell not on PATH in minimized cmd windows

### Fix
- Copied `mvnw.cmd` + `.mvn/wrapper/` to: discovery, gateway, reporting, sheet-import, results
- Rewrote `start-local.bat` to generate per-service launcher `.bat` files in `logs/` folder
- Each launcher sets `JAVA_HOME`, `PowerShell`, `System32` on PATH before running mvnw.cmd
- Created `stop-local.bat` (kills by window title + port-based PID fallback)

### Service Port Map
| Service | Port |
|---------|------|
| Discovery (Eureka) | 8761 |
| OAuth | 8080 |
| Gateway | 8082 |
| Reporting | 8083 |
| Portfolio | 8084 |
| Alert | 8087 |
| Results (Java) | 8088 |
| Results (Python) | 8090 |
| Sheet Import | 8091 |
| Announcement | 8092 |

### Build Commands (from Git Bash)
```bash
export PATH="/c/Windows/System32/WindowsPowerShell/v1.0:/c/Windows/System32:$PATH"
export JAVA_HOME="C:/Program Files/OpenLogic/jdk-17.0.14.7-hotspot"
cd <service-dir> && ./mvnw.cmd compile -DskipTests -q
```

### Restart Single Service Script
Created `restart.bat` for restarting individual services without stopping everything:
```cmd
restart --gateway
restart --portfolio
restart --oauth
restart --sheet-import
```
Kills the process on the service port, regenerates launcher, starts in minimized window, polls until port is listening. Run `restart --help` for full list.

---

## Connecting to PostgreSQL Database

### Connection Details
- **Host**: localhost
- **Port**: 5432
- **Username**: postgres
- **Password**: postgres
- **psql binary**: `C:\Program Files\PostgreSQL\17\bin\psql.exe`

### How to Connect (from Git Bash in Claude Code)
psql is NOT on PATH. Use the full path with `PGPASSWORD` env var:
```bash
export PGPASSWORD=postgres && "/c/Program Files/PostgreSQL/17/bin/psql.exe" -h localhost -p 5432 -U postgres -d <database_name> -c "<SQL query>"
```

**Important**: `cmd /c` based psql calls do NOT work reliably due to quoting issues. Always use the Git Bash path format (`/c/Program Files/...`) directly.

### Database Names
| Service | Database |
|---------|----------|
| Portfolio | `portfolio_db` |
| OAuth | `oauth_db` |
| Reporting | `reporting_db` |
| Sheet Import | `sheet_import_db` |
| Alert | `alert_db` |
| Announcement | `announcement_db` |
| Results | `results_db` |

### Example Queries
```bash
# List all databases
export PGPASSWORD=postgres && "/c/Program Files/PostgreSQL/17/bin/psql.exe" -h localhost -p 5432 -U postgres -l

# Query portfolio snapshots
export PGPASSWORD=postgres && "/c/Program Files/PostgreSQL/17/bin/psql.exe" -h localhost -p 5432 -U postgres -d portfolio_db -c "SELECT snapshot_date, normalized_value FROM portfolio_snapshots ORDER BY snapshot_date;"

# Query transactions
export PGPASSWORD=postgres && "/c/Program Files/PostgreSQL/17/bin/psql.exe" -h localhost -p 5432 -U postgres -d portfolio_db -c "SELECT transaction_date, ticker, type, quantity, price FROM transactions WHERE type='BUY' ORDER BY transaction_date;"

# Check distinct users
export PGPASSWORD=postgres && "/c/Program Files/PostgreSQL/17/bin/psql.exe" -h localhost -p 5432 -U postgres -d portfolio_db -c "SELECT DISTINCT user_sub, COUNT(*) as snapshots FROM portfolio_snapshots GROUP BY user_sub;"
```

---

## Monitoring Setup (Apr 2026)

### Loki + Grafana + Promtail (Centralized Logging)

**Architecture:**
```
Java services → logback-spring.xml → logs/*.log
                                          ↓
                                    Promtail (Docker)
                                    reads logs/*.log
                                          ↓
                                    Loki (Docker)
                                    stores + indexes
                                          ↓
                                    Grafana (Docker, port 3001)
                                    queries + displays
```

**Key files:**
- `docker-compose.monitoring.yml` — runs Grafana (port **3001**), Loki, Promtail, Prometheus, postgres-exporter
- `monitoring/promtail-config.yml` — scrapes `./logs/*.log`, extracts `service` and `level` labels
- `monitoring/grafana/dashboards/service-logs.json` — Logs dashboard with service dropdown, error/warn panels
- `monitoring/grafana/dashboards/service-metrics.json` — CPU, memory, HTTP metrics from Prometheus

**Grafana URL**: http://localhost:3001 (NOT 3000)

**Loki query examples:**
```
{job="stockanalytics", service="reporting-service"}
{job="stockanalytics", level="ERROR"}
{service=~"gateway-service|portfolio-service"} |= "exception"
```

**Note**: `stockanalytics` is the Promtail **job label**, not a service name. All services share this job label.

### Prometheus Metrics

All services expose `/actuator/prometheus`. Key metrics:
```
process_cpu_usage          — CPU of that Java process (0.0 to 1.0)
system_cpu_usage           — CPU of entire machine
jvm_memory_used_bytes{area="heap"}  — Heap memory per service
```

**Query examples (via curl):**
```bash
# Current CPU per service
curl -s "http://localhost:9090/api/v1/query?query=process_cpu_usage"

# Memory usage per service
curl -s "http://localhost:9090/api/v1/query?query=sum(jvm_memory_used_bytes{area='heap'}) by (application)"

# Memory over 12 hours (check for leaks)
curl -s "http://localhost:9090/api/v1/query_range?query=sum(jvm_memory_used_bytes{area='heap'}) by (application)&start=$(date -u -d '12 hours ago' +%Y-%m-%dT%H:%M:%SZ)&end=$(date -u +%Y-%m-%dT%H:%M:%SZ)&step=300"
```

### Distributed Tracing (Prepared but Blocked)

Micrometer Tracing + Brave setup is prepared:
- `logback-spring.xml` patterns include `[%X{traceId:-},%X{spanId:-}]` — shows `[,]` without bridge jar
- `application.properties` has `management.tracing.sampling.probability=1.0` and `management.tracing.propagation.type=b3`
- `promtail-config.yml` has traceId extraction regex
- `monitoring/grafana/provisioning/datasources/loki.yml` has derivedFields for clickable traceId links

**Blocked**: `micrometer-tracing-bridge-brave` jar cannot be downloaded due to Zscaler corporate proxy blocking Maven Central. The dependency was removed from all 9 pom.xml files. To enable tracing, manually download the jar and install it in the local `.m2` repository, then re-add the dependency.

---

## Analytics Graph Spike Fix (Apr 2026)

### Problem
Portfolio analytics chart vs indices showed intermittent huge spikes (e.g., normalized_value jumping from ~90 to 156 on April 10, then back to ~96 on April 11).

### Root Causes (Three Issues)

#### 1. Bad stored `normalized_value` in portfolio_snapshots table
The `captureSnapshot()` method was intermittently calculating wrong normalized values. For example:
```
Apr 2:  89.79  ← correct
Apr 3:  126.20 ← SPIKE (should be ~89.79)
Apr 9:  149.31 ← SPIKE (should be ~94.29)
Apr 10: 156.01 ← SPIKE (should be ~95.88)
Apr 11: 95.96  ← correct again
```
The `total_wealth` values were consistent (no jumps) — the bug was purely in the normalized calculation during snapshot creation.

#### 2. Frontend chart alignment by array index instead of by date
Each index has slightly different trading days (e.g., Nifty 50 = 249 days, Midcap = 248 days). The old code sampled by array position (`j % sampleRate`), so when one index had fewer points, all subsequent values shifted by one position — April 9's value plotted at April 10's label.

#### 3. Nifty Midcap one day less data
Yahoo Finance returns `null` close price for `^CRSMID` on certain trading days. The backend skipped nulls entirely (`if (closes.get(i).isNull()) continue;`), resulting in fewer data points.

### Fixes Applied

#### Fix 1: DB data — Recalculated all normalized_value
```sql
-- Recalculate for user 110338560037650953472 (main user)
-- firstWealth = 1920850.90, firstTotalBuys = 2039144.22
WITH buy_cumulative AS (
    SELECT transaction_date,
           SUM(price * quantity) OVER (ORDER BY transaction_date, id) as cum_buys
    FROM transactions
    WHERE user_sub = '110338560037650953472' AND type = 'BUY'
),
buys_by_date AS (
    SELECT transaction_date, MAX(cum_buys) as total_buys_asof
    FROM buy_cumulative
    GROUP BY transaction_date
)
UPDATE portfolio_snapshots ps
SET normalized_value = ROUND(
    (ps.total_wealth / (
        1920850.90 + GREATEST(
            COALESCE(
                (SELECT b.total_buys_asof FROM buys_by_date b
                 WHERE b.transaction_date <= ps.snapshot_date
                 ORDER BY b.transaction_date DESC LIMIT 1),
                2039144.22
            ) - 2039144.22, 0
        )
    )) * 100, 2
)
WHERE ps.user_sub = '110338560037650953472';
```
Applied to all 3 users (206 rows total). All spikes eliminated.

#### Fix 2: Backend — Recalculate normalized values on-the-fly
**File**: `portfolio-service/.../service/PortfolioReturnsService.java` — `getPortfolioHistory()`

Instead of trusting stored `normalized_value`, now loads all BUY transactions into a `TreeMap<LocalDate, BigDecimal>` (1 DB query), then recalculates for each snapshot using O(log n) `floorEntry()` lookups:
```java
TreeMap<LocalDate, BigDecimal> cumulativeBuys = new TreeMap<>();
// ... build from transactions ...

for (PortfolioSnapshot s : snapshots) {
    Map.Entry<LocalDate, BigDecimal> entry = cumulativeBuys.floorEntry(s.getSnapshotDate());
    BigDecimal buysAsOfDate = entry != null ? entry.getValue() : baseBuys;
    BigDecimal capitalAdded = buysAsOfDate.subtract(baseBuys);
    BigDecimal adjustedBase = firstWealth.add(capitalAdded);
    double normalized = snapshotWealth / adjustedBase * 100;
}
```

#### Fix 3: Frontend — Date-based alignment
**File**: `gateway-service/.../static/portfolio-analytics.html` — `renderHistoricalChart()`

- Builds union of ALL dates from all indices (deduplicated, sorted)
- Creates `date→value` lookup maps for each index
- Samples from common date axis, looks up values **by date** (not array index)
- Adds `spanGaps: true` to bridge missing days

#### Fix 4: Backend — Carry forward null close prices
**File**: `sheet-import-service/.../service/YahooFinanceService.java` — `fetchIndexHistoricalData()`

Instead of skipping null close prices, carry forward the previous day's close:
```java
double lastValidClose = 0;
for (int i = 0; i < timestamps.size(); i++) {
    if (closes.get(i).isNull()) {
        if (lastValidClose == 0) continue;  // skip only if no prior data
    } else {
        lastValidClose = closes.get(i).asDouble();
    }
    double closePrice = lastValidClose;  // use last valid
    // ... rest of processing
}
```

### Users in portfolio_snapshots
| user_sub | snapshots | max_positions |
|----------|-----------|---------------|
| 110338560037650953472 | 75 | 16 |
| 117051773259068023186 | 66 | 1 |
| 116574928339546453814 | 65 | 40 |
