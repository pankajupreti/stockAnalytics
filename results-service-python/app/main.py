"""
Results Service - FastAPI application for parsing quarterly financial results from PDFs.
Supports both text-based and scanned (OCR) PDFs.
Includes PostgreSQL caching to avoid repeated Screener.in calls.
"""

import asyncio
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Optional, List, Dict, Any
import logging

from .pdf_parser import PdfParserService
from .table_extractor import QuarterlyResultsExtractor
from .screener_scraper import ScreenerScraperService
from .database import db
from .rabbitmq_consumer import run_consumer

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Initialize services early (needed by lifespan)
pdf_parser = PdfParserService()
results_extractor = QuarterlyResultsExtractor()
screener_scraper = ScreenerScraperService()

# Global reference to RabbitMQ consumer for cleanup
rabbitmq_consumer = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup and shutdown events."""
    global rabbitmq_consumer

    # Startup: Connect to database
    logger.info("Starting up - connecting to database...")
    db_connected = await db.connect()
    if db_connected:
        logger.info("Database connected successfully")
    else:
        logger.warning("Database connection failed - caching disabled")

    # Startup: Start RabbitMQ consumer
    logger.info("Starting RabbitMQ consumer...")
    try:
        rabbitmq_consumer = await run_consumer(screener_scraper, db)
        if rabbitmq_consumer:
            logger.info("RabbitMQ consumer started successfully")
        else:
            logger.warning("RabbitMQ consumer not available - event processing disabled")
    except Exception as e:
        logger.warning(f"Failed to start RabbitMQ consumer: {e}")

    yield

    # Shutdown: Stop RabbitMQ consumer
    if rabbitmq_consumer:
        logger.info("Stopping RabbitMQ consumer...")
        await rabbitmq_consumer.stop()

    # Shutdown: Close database connection
    logger.info("Shutting down - closing database...")
    await db.close()


app = FastAPI(
    title="Results Service",
    description="PDF parsing service for Indian quarterly financial results with PostgreSQL caching",
    version="1.1.0",
    lifespan=lifespan
)

# CORS for gateway
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class ParseRequest(BaseModel):
    pdf_url: Optional[str] = None
    file_path: Optional[str] = None
    ticker: Optional[str] = None


class ParsedMetrics(BaseModel):
    revenue: Optional[float] = None
    other_income: Optional[float] = None
    total_income: Optional[float] = None
    total_expenses: Optional[float] = None
    ebitda: Optional[float] = None
    pbt: Optional[float] = None
    tax: Optional[float] = None
    pat: Optional[float] = None
    eps_basic: Optional[float] = None
    eps_diluted: Optional[float] = None
    # Bank specific
    nii: Optional[float] = None
    provisions: Optional[float] = None


class ParseResponse(BaseModel):
    success: bool
    ticker: Optional[str] = None
    quarter: Optional[str] = None
    fiscal_year: Optional[int] = None
    company_type: str = "REGULAR"
    metrics: Optional[ParsedMetrics] = None
    source: str = "pdf"
    text_length: int = 0
    pages_processed: int = 0
    ocr_used: bool = False
    error: Optional[str] = None


@app.get("/health")
async def health_check():
    """Health check endpoint."""
    return {
        "status": "healthy",
        "service": "results-service-python",
        "database": "connected" if db.pool else "disconnected"
    }


@app.get("/api/results/cache/stats")
async def get_cache_stats():
    """Get cache statistics."""
    if not db.pool:
        return {"cacheEnabled": False, "message": "Database not connected"}

    try:
        async with db.pool.acquire() as conn:
            # Count total cached results
            total = await conn.fetchval("SELECT COUNT(*) FROM quarterly_results")
            # Count unique tickers
            tickers = await conn.fetchval("SELECT COUNT(DISTINCT ticker) FROM quarterly_results")
            # Get most recently cached
            recent = await conn.fetch(
                """
                SELECT ticker, MAX(fetched_at) as last_fetched
                FROM quarterly_results
                GROUP BY ticker
                ORDER BY last_fetched DESC
                LIMIT 5
                """
            )

        return {
            "cacheEnabled": True,
            "totalResults": total,
            "uniqueTickers": tickers,
            "recentlyUpdated": [{"ticker": r["ticker"], "lastFetched": str(r["last_fetched"])} for r in recent]
        }
    except Exception as e:
        logger.error(f"Error getting cache stats: {e}")
        return {"cacheEnabled": True, "error": str(e)}


@app.post("/api/results/parse", response_model=ParseResponse)
async def parse_pdf(request: ParseRequest):
    """
    Parse a PDF and extract quarterly financial results.

    Accepts either a PDF URL or a local file path.
    """
    try:
        if not request.pdf_url and not request.file_path:
            raise HTTPException(status_code=400, detail="Either pdf_url or file_path is required")

        logger.info(f"Parsing PDF: url={request.pdf_url}, path={request.file_path}")

        # Extract text from PDF
        if request.file_path:
            result = pdf_parser.extract_from_file(request.file_path)
        else:
            result = await pdf_parser.extract_from_url(request.pdf_url)

        if not result["success"]:
            return ParseResponse(
                success=False,
                error=result.get("error", "Failed to extract text from PDF")
            )

        text = result["text"]
        logger.info(f"Extracted {len(text)} characters from PDF")

        # Extract financial metrics from text
        metrics = results_extractor.extract_metrics(text)
        quarter_info = results_extractor.extract_quarter_info(text)
        is_bank = results_extractor.is_bank_pdf(text)

        return ParseResponse(
            success=True,
            ticker=request.ticker,
            quarter=quarter_info.get("quarter", "Q3"),
            fiscal_year=quarter_info.get("fiscal_year", 2026),
            company_type="BANK" if is_bank else "REGULAR",
            metrics=ParsedMetrics(**metrics) if metrics else None,
            source="pdf",
            text_length=len(text),
            pages_processed=result.get("pages", 0),
            ocr_used=result.get("ocr_used", False)
        )

    except Exception as e:
        logger.error(f"Error parsing PDF: {e}", exc_info=True)
        return ParseResponse(
            success=False,
            error=str(e)
        )


@app.get("/api/results/parse")
async def parse_pdf_get(
    pdf_url: Optional[str] = Query(None, description="URL of the PDF to parse"),
    file_path: Optional[str] = Query(None, description="Local file path of PDF"),
    ticker: Optional[str] = Query(None, description="Stock ticker symbol")
):
    """GET endpoint for PDF parsing (convenience for testing)."""
    request = ParseRequest(pdf_url=pdf_url, file_path=file_path, ticker=ticker)
    return await parse_pdf(request)


@app.get("/api/results/test-local")
async def test_local_pdf(file_path: str = Query(..., description="Path to local PDF file")):
    """Test endpoint for parsing a local PDF file."""
    request = ParseRequest(file_path=file_path)
    return await parse_pdf(request)


@app.post("/api/results/parse-tables")
async def parse_tables(request: ParseRequest):
    """
    Parse PDF using table extraction (pdfplumber/camelot).
    Better for structured tables in quarterly results.
    """
    try:
        if not request.pdf_url and not request.file_path:
            raise HTTPException(status_code=400, detail="Either pdf_url or file_path is required")

        logger.info(f"Parsing tables from PDF: url={request.pdf_url}, path={request.file_path}")

        # Get PDF file path
        if request.file_path:
            pdf_path = request.file_path
        else:
            pdf_path = await pdf_parser.download_pdf(request.pdf_url)

        if not pdf_path:
            return {"success": False, "error": "Failed to get PDF"}

        # Extract tables using pdfplumber
        tables = results_extractor.extract_tables_from_pdf(pdf_path)

        # Parse metrics from tables
        metrics = results_extractor.parse_metrics_from_tables(tables)

        return {
            "success": True,
            "ticker": request.ticker,
            "tables_found": len(tables),
            "metrics": metrics
        }

    except Exception as e:
        logger.error(f"Error parsing tables: {e}", exc_info=True)
        return {"success": False, "error": str(e)}


@app.get("/api/results/ticker/{ticker}")
async def get_results_for_ticker(
    ticker: str,
    require_current_quarter: bool = Query(True, description="If true, cache is stale if it doesn't have current quarter results"),
    result_type: str = Query("consolidated", description="Result type: 'consolidated', 'standalone', or 'auto'")
):
    """
    Get quarterly results for a ticker.
    First checks PostgreSQL cache, then fetches from Screener.in if cache miss/stale.

    Args:
        ticker: Stock ticker symbol
        require_current_quarter: If true, cache is considered stale if it doesn't have current quarter
        result_type: 'consolidated' (default), 'standalone', or 'auto' (tries consolidated first)

    Smart cache invalidation: By default (require_current_quarter=True), if the cache
    doesn't have the current results season quarter (e.g., Q3 FY26 in Jan-Feb),
    it will fetch fresh data from Screener.in even if the cache is within TTL.
    """
    try:
        ticker = ticker.upper().strip()
        logger.info(f"Getting results for ticker: {ticker} (type={result_type}, require_current_quarter={require_current_quarter})")

        # Step 1: Check database cache first (with smart quarter check)
        cached_results = await db.get_cached_results(ticker, require_current_quarter=require_current_quarter, result_type=result_type)
        if cached_results:
            logger.info(f"Cache hit for {ticker} - returning {len(cached_results)} quarters")
            actual_type = cached_results[0].get("resultType", "consolidated") if cached_results else result_type
            return {
                "ticker": ticker,
                "results": cached_results,
                "resultCount": len(cached_results),
                "resultType": actual_type,
                "source": "cache",
                "companyType": "REGULAR"
            }

        # Step 2: Cache miss or stale - fetch from Screener.in
        logger.info(f"Cache miss/stale for {ticker} - fetching from Screener.in")
        screener_result = await screener_scraper.fetch_quarterly_results(ticker, result_type=result_type)

        if screener_result.get("success") and screener_result.get("results"):
            results = screener_result["results"]
            actual_type = screener_result.get("resultType", result_type)

            # Step 3: Save to cache for future requests
            saved = await db.save_results(ticker, results)
            if saved:
                logger.info(f"Cached {len(results)} {actual_type} quarters for {ticker}")

            return {
                "ticker": ticker,
                "results": results,
                "resultCount": len(results),
                "resultType": actual_type,
                "source": "screener",
                "companyType": "REGULAR"
            }

        # Step 4: Screener.in also failed - return old cache if available (fallback)
        old_cache = await db.get_cached_results(ticker, require_current_quarter=False, result_type=result_type)
        if old_cache:
            logger.info(f"Screener failed, returning old cache for {ticker}")
            actual_type = old_cache[0].get("resultType", "consolidated") if old_cache else result_type
            return {
                "ticker": ticker,
                "results": old_cache,
                "resultCount": len(old_cache),
                "resultType": actual_type,
                "source": "cache (stale)",
                "companyType": "REGULAR",
                "warning": "Current quarter results not available yet on Screener.in"
            }

        return {
            "ticker": ticker,
            "results": [],
            "resultCount": 0,
            "resultType": result_type,
            "source": "none",
            "error": screener_result.get("error", "No results found")
        }

    except Exception as e:
        logger.error(f"Error getting results for {ticker}: {e}", exc_info=True)
        return {"ticker": ticker, "results": [], "error": str(e)}


@app.get("/api/results/ticker/{ticker}/both")
async def get_both_result_types(ticker: str):
    """
    Get BOTH consolidated and standalone results for a ticker.
    Useful for comparing the two or when user wants to see both.
    """
    try:
        ticker = ticker.upper().strip()
        logger.info(f"Fetching both result types for: {ticker}")

        # Fetch both from Screener
        result = await screener_scraper.fetch_both_result_types(ticker)

        if result.get("success"):
            consolidated = result.get("consolidated", [])
            standalone = result.get("standalone", [])

            # Save both to cache
            if consolidated:
                await db.save_results(ticker, consolidated)
            if standalone:
                await db.save_results(ticker, standalone)

            return {
                "ticker": ticker,
                "hasConsolidated": result.get("hasConsolidated", False),
                "hasStandalone": result.get("hasStandalone", False),
                "consolidated": {
                    "results": consolidated,
                    "resultCount": len(consolidated)
                },
                "standalone": {
                    "results": standalone,
                    "resultCount": len(standalone)
                },
                "source": "screener"
            }

        return {
            "ticker": ticker,
            "hasConsolidated": False,
            "hasStandalone": False,
            "error": result.get("error", "Failed to fetch results")
        }

    except Exception as e:
        logger.error(f"Error fetching both result types for {ticker}: {e}", exc_info=True)
        return {"ticker": ticker, "error": str(e)}


@app.get("/api/results/screener/{ticker}")
async def get_results_from_screener(ticker: str):
    """
    Fetch results directly from Screener.in (bypasses cache check, but saves to cache).
    """
    try:
        ticker = ticker.upper().strip()
        logger.info(f"Fetching Screener results for: {ticker}")

        result = await screener_scraper.fetch_quarterly_results(ticker)

        if result.get("success"):
            results = result.get("results", [])

            # Save to cache for future requests
            if results:
                saved = await db.save_results(ticker, results)
                if saved:
                    logger.info(f"Cached {len(results)} quarters for {ticker}")

            return {
                "ticker": ticker,
                "results": results,
                "resultCount": len(results),
                "source": "screener",
                "companyType": "REGULAR"
            }

        return {
            "ticker": ticker,
            "results": [],
            "resultCount": 0,
            "source": "none",
            "error": result.get("error", "Failed to fetch from Screener")
        }

    except Exception as e:
        logger.error(f"Error fetching from Screener for {ticker}: {e}", exc_info=True)
        return {"ticker": ticker, "results": [], "error": str(e)}


@app.post("/api/results/refresh/{ticker}")
async def refresh_results(ticker: str):
    """
    Force refresh results for a ticker.
    Invalidates cache and fetches fresh data from Screener.in.
    """
    try:
        ticker = ticker.upper().strip()
        logger.info(f"Force refreshing results for: {ticker}")

        # Step 1: Invalidate existing cache
        await db.invalidate_cache(ticker)
        logger.info(f"Invalidated cache for {ticker}")

        # Step 2: Fetch fresh from Screener.in
        result = await screener_scraper.fetch_quarterly_results(ticker)

        if result.get("success"):
            results = result.get("results", [])

            # Step 3: Save to cache
            if results:
                saved = await db.save_results(ticker, results)
                if saved:
                    logger.info(f"Cached {len(results)} fresh quarters for {ticker}")

            return {
                "ticker": ticker,
                "results": results,
                "resultCount": len(results),
                "source": "screener",
                "companyType": "REGULAR"
            }

        return {
            "ticker": ticker,
            "results": [],
            "resultCount": 0,
            "source": "none",
            "error": result.get("error", "Failed to refresh results")
        }

    except Exception as e:
        logger.error(f"Error refreshing results for {ticker}: {e}", exc_info=True)
        return {"ticker": ticker, "results": [], "error": str(e)}


@app.post("/api/results/auto-refresh/{ticker}")
async def auto_refresh_results(ticker: str, delay_minutes: int = 30):
    """
    Auto-refresh endpoint called by announcement-service when financial results are announced.
    Waits for Screener.in to update (usually 30-60 mins after BSE announcement),
    then fetches and caches the data.

    This is designed to be called asynchronously by the announcement-service webhook.
    """
    try:
        ticker = ticker.upper().strip()
        logger.info(f"Auto-refresh triggered for {ticker}, will fetch from Screener after {delay_minutes} minutes")

        # Note: In production, this should use a background task queue (Celery, etc.)
        # For now, we just fetch immediately since the caller can delay the call

        # Invalidate existing cache to force fresh fetch
        await db.invalidate_cache(ticker)

        # Fetch fresh from Screener.in
        result = await screener_scraper.fetch_quarterly_results(ticker)

        if result.get("success"):
            results = result.get("results", [])
            if results:
                saved = await db.save_results(ticker, results)
                logger.info(f"Auto-refresh: Cached {len(results)} quarters for {ticker}")
                return {
                    "ticker": ticker,
                    "success": True,
                    "resultCount": len(results),
                    "message": f"Refreshed {len(results)} quarters from Screener"
                }

        return {
            "ticker": ticker,
            "success": False,
            "message": result.get("error", "No results found on Screener")
        }

    except Exception as e:
        logger.error(f"Auto-refresh error for {ticker}: {e}", exc_info=True)
        return {"ticker": ticker, "success": False, "error": str(e)}


@app.post("/api/results/bulk-refresh")
async def bulk_refresh_results(tickers: List[str] = Query(...)):
    """
    Refresh results for multiple tickers (e.g., portfolio stocks during results season).
    Returns status for each ticker.
    """
    try:
        results = {}
        for ticker in tickers:
            ticker = ticker.upper().strip()
            if ticker.startswith("NSE:"):
                ticker = ticker[4:]

            # Fetch from Screener
            result = await screener_scraper.fetch_quarterly_results(ticker)
            if result.get("success") and result.get("results"):
                await db.save_results(ticker, result["results"])
                results[ticker] = {"success": True, "quarters": len(result["results"])}
            else:
                results[ticker] = {"success": False, "error": result.get("error", "No data")}

        success_count = sum(1 for r in results.values() if r.get("success"))
        return {
            "results": results,
            "successCount": success_count,
            "totalCount": len(tickers)
        }

    except Exception as e:
        logger.error(f"Bulk refresh error: {e}", exc_info=True)
        return {"error": str(e)}


def get_current_quarter_info():
    """
    Get current quarter and fiscal year based on Indian fiscal year (April-March).

    Indian FY quarters:
    - Q1: Apr-Jun (results announced Jul-Aug)
    - Q2: Jul-Sep (results announced Oct-Nov)
    - Q3: Oct-Dec (results announced Jan-Feb)
    - Q4: Jan-Mar (results announced Apr-May)

    Returns the quarter whose results are currently being announced.
    """
    from datetime import datetime
    now = datetime.now()
    month = now.month
    year = now.year

    # Determine which quarter's results are being announced
    # Results are typically announced 1-2 months after quarter ends
    if month in [1, 2]:  # Jan-Feb: Q3 results (Oct-Dec)
        quarter = "Q3"
        fiscal_year = year  # FY ends in March of this year
    elif month in [4, 5]:  # Apr-May: Q4 results (Jan-Mar)
        quarter = "Q4"
        fiscal_year = year  # FY just ended in March
    elif month in [7, 8]:  # Jul-Aug: Q1 results (Apr-Jun)
        quarter = "Q1"
        fiscal_year = year + 1  # New FY started in April
    elif month in [10, 11]:  # Oct-Nov: Q2 results (Jul-Sep)
        quarter = "Q2"
        fiscal_year = year + 1  # FY started in April
    else:
        # In between months (Mar, Jun, Sep, Dec) - use previous logic
        if month == 3:
            quarter = "Q3"
            fiscal_year = year
        elif month == 6:
            quarter = "Q4"
            fiscal_year = year
        elif month == 9:
            quarter = "Q1"
            fiscal_year = year + 1
        else:  # month == 12
            quarter = "Q2"
            fiscal_year = year + 1

    return quarter, fiscal_year


@app.get("/api/results/good-results")
async def get_good_results(
    min_pat_yoy: Optional[float] = Query(None, description="Minimum PAT YoY growth %"),
    min_revenue_yoy: Optional[float] = Query(None, description="Minimum Revenue YoY growth %"),
    min_pat_qoq: Optional[float] = Query(None, description="Minimum PAT QoQ growth %"),
    min_revenue_qoq: Optional[float] = Query(None, description="Minimum Revenue QoQ growth %"),
    min_pbt_yoy: Optional[float] = Query(None, description="Minimum PBT YoY growth %"),
    min_pbt_qoq: Optional[float] = Query(None, description="Minimum PBT QoQ growth %"),
    current_quarter_only: bool = Query(False, description="Filter to show only current quarter results"),
    quarter: Optional[str] = Query(None, description="Specific quarter (Q1/Q2/Q3/Q4)"),
    fiscal_year: Optional[int] = Query(None, description="Specific fiscal year (e.g., 2026)"),
    result_type: str = Query("consolidated", description="Result type: 'consolidated' or 'standalone'"),
    days: int = Query(90, description="Look for results in last N days"),
    limit: int = Query(50, description="Max results to return")
):
    """
    Get stocks with "good results" - positive PAT and Revenue growth.
    Supports both YoY and QoQ filters (all optional, at least one required).

    Criteria for "good results" (any combination):
    - PAT YoY growth >= min_pat_yoy
    - Revenue YoY growth >= min_revenue_yoy
    - PAT QoQ growth >= min_pat_qoq
    - Revenue QoQ growth >= min_revenue_qoq
    - PBT YoY growth >= min_pbt_yoy
    - PBT QoQ growth >= min_pbt_qoq

    Quarter filters:
    - current_quarter_only: Auto-detect current quarter (e.g., Q3 FY26 in Jan-Feb)
    - quarter + fiscal_year: Specific quarter (e.g., Q3 + 2026)
    """
    try:
        # No default filters - if all are None, return all results without growth filtering
        # This allows the PEAD scanner "Custom" and "Negative Drifters" presets to work

        # Determine quarter filter
        target_quarter = None
        target_fiscal_year = None

        if quarter and fiscal_year:
            # Explicit quarter/fiscal_year takes priority (e.g., fallback from Java service)
            target_quarter = quarter.upper()
            target_fiscal_year = fiscal_year
            logger.info(f"Using explicit quarter: {target_quarter} FY{target_fiscal_year}")
        elif current_quarter_only:
            target_quarter, target_fiscal_year = get_current_quarter_info()
            logger.info(f"Current quarter detected: {target_quarter} FY{target_fiscal_year}")

        logger.info(f"Getting good results: PAT YoY>={min_pat_yoy}%, Rev YoY>={min_revenue_yoy}%, "
                    f"PAT QoQ>={min_pat_qoq}%, Rev QoQ>={min_revenue_qoq}%, "
                    f"PBT YoY>={min_pbt_yoy}%, PBT QoQ>={min_pbt_qoq}%, days={days}, "
                    f"quarter={target_quarter}, fiscalYear={target_fiscal_year}, resultType={result_type}")

        if not db.pool:
            return {"stocks": [], "error": "Database not connected"}

        async with db.pool.acquire() as conn:
            # Build quarter filter clause
            quarter_filter = ""
            if target_quarter and target_fiscal_year:
                quarter_filter = f"AND quarter = '{target_quarter}' AND fiscal_year = {target_fiscal_year}"

            # Build result type filter clause
            result_type_filter = f"AND (result_type = '{result_type}' OR result_type IS NULL)"

            # Query to find stocks with recent results that have good YoY and/or QoQ growth
            query = f"""
                WITH latest_results AS (
                    -- Get latest quarter for each stock (recently fetched)
                    SELECT DISTINCT ON (ticker)
                        ticker, quarter, fiscal_year, quarter_label, result_type,
                        revenue, pat, pbt, ebitda, ebitda_margin, pat_margin, eps_basic,
                        fetched_at
                    FROM quarterly_results
                    WHERE fetched_at > NOW() - INTERVAL '{days} days'
                        {quarter_filter}
                        {result_type_filter}
                    ORDER BY ticker, fiscal_year DESC, quarter DESC
                ),
                prev_quarter AS (
                    -- Get previous quarter for QoQ comparison (same result_type)
                    SELECT
                        qr.ticker,
                        qr.revenue as prev_q_revenue,
                        qr.pat as prev_q_pat,
                        qr.pbt as prev_q_pbt
                    FROM quarterly_results qr
                    INNER JOIN latest_results lr ON lr.ticker = qr.ticker
                        AND (qr.result_type = lr.result_type OR (qr.result_type IS NULL AND lr.result_type IS NULL))
                    WHERE (qr.fiscal_year = lr.fiscal_year AND qr.quarter < lr.quarter)
                       OR (qr.fiscal_year = lr.fiscal_year - 1 AND lr.quarter = 'Q1')
                    ORDER BY qr.ticker, qr.fiscal_year DESC, qr.quarter DESC
                ),
                prev_quarter_dedup AS (
                    SELECT DISTINCT ON (ticker) * FROM prev_quarter
                    ORDER BY ticker
                ),
                yoy_results AS (
                    -- Get same quarter from last year for YoY comparison (same result_type)
                    SELECT
                        qr.ticker,
                        qr.revenue as prev_y_revenue,
                        qr.pat as prev_y_pat,
                        qr.pbt as prev_y_pbt
                    FROM quarterly_results qr
                    INNER JOIN latest_results lr ON lr.ticker = qr.ticker
                        AND qr.quarter = lr.quarter
                        AND qr.fiscal_year = lr.fiscal_year - 1
                        AND (qr.result_type = lr.result_type OR (qr.result_type IS NULL AND lr.result_type IS NULL))
                ),
                growth_calc AS (
                    SELECT
                        lr.ticker,
                        lr.quarter_label,
                        lr.quarter,
                        lr.fiscal_year,
                        lr.result_type,
                        lr.revenue,
                        lr.pat,
                        lr.pbt,
                        lr.ebitda,
                        lr.ebitda_margin,
                        lr.pat_margin,
                        lr.eps_basic,
                        lr.fetched_at,
                        yr.prev_y_revenue,
                        yr.prev_y_pat,
                        yr.prev_y_pbt,
                        pq.prev_q_revenue,
                        pq.prev_q_pat,
                        pq.prev_q_pbt,
                        CASE WHEN yr.prev_y_revenue IS NOT NULL AND yr.prev_y_revenue != 0 THEN
                            ((lr.revenue - yr.prev_y_revenue) / ABS(yr.prev_y_revenue) * 100)
                        ELSE NULL END as revenue_yoy,
                        CASE WHEN yr.prev_y_pat IS NOT NULL AND yr.prev_y_pat != 0 THEN
                            ((lr.pat - yr.prev_y_pat) / ABS(yr.prev_y_pat) * 100)
                        ELSE NULL END as pat_yoy,
                        CASE WHEN yr.prev_y_pbt IS NOT NULL AND yr.prev_y_pbt != 0 THEN
                            ((lr.pbt - yr.prev_y_pbt) / ABS(yr.prev_y_pbt) * 100)
                        ELSE NULL END as pbt_yoy,
                        CASE WHEN pq.prev_q_revenue IS NOT NULL AND pq.prev_q_revenue != 0 THEN
                            ((lr.revenue - pq.prev_q_revenue) / ABS(pq.prev_q_revenue) * 100)
                        ELSE NULL END as revenue_qoq,
                        CASE WHEN pq.prev_q_pat IS NOT NULL AND pq.prev_q_pat != 0 THEN
                            ((lr.pat - pq.prev_q_pat) / ABS(pq.prev_q_pat) * 100)
                        ELSE NULL END as pat_qoq,
                        CASE WHEN pq.prev_q_pbt IS NOT NULL AND pq.prev_q_pbt != 0 THEN
                            ((lr.pbt - pq.prev_q_pbt) / ABS(pq.prev_q_pbt) * 100)
                        ELSE NULL END as pbt_qoq
                    FROM latest_results lr
                    LEFT JOIN yoy_results yr ON lr.ticker = yr.ticker
                    LEFT JOIN prev_quarter_dedup pq ON lr.ticker = pq.ticker
                )
                SELECT * FROM growth_calc
                WHERE 1=1
                    {f'AND pat_yoy >= {min_pat_yoy}' if min_pat_yoy is not None else ''}
                    {f'AND revenue_yoy >= {min_revenue_yoy}' if min_revenue_yoy is not None else ''}
                    {f'AND pat_qoq >= {min_pat_qoq}' if min_pat_qoq is not None else ''}
                    {f'AND revenue_qoq >= {min_revenue_qoq}' if min_revenue_qoq is not None else ''}
                    {f'AND pbt_yoy >= {min_pbt_yoy}' if min_pbt_yoy is not None else ''}
                    {f'AND pbt_qoq >= {min_pbt_qoq}' if min_pbt_qoq is not None else ''}
                ORDER BY COALESCE(pat_yoy, 0) + COALESCE(pat_qoq, 0) DESC
                LIMIT {limit}
            """

            rows = await conn.fetch(query)

            stocks = []
            for row in rows:
                stocks.append({
                    "ticker": row["ticker"],
                    "quarterLabel": row["quarter_label"],
                    "quarter": row["quarter"],
                    "fiscalYear": row["fiscal_year"],
                    "resultType": row["result_type"] if row["result_type"] else "consolidated",
                    "revenue": float(row["revenue"]) if row["revenue"] else None,
                    "pat": float(row["pat"]) if row["pat"] else None,
                    "pbt": float(row["pbt"]) if row["pbt"] else None,
                    "ebitda": float(row["ebitda"]) if row["ebitda"] else None,
                    "ebitdaMargin": float(row["ebitda_margin"]) if row["ebitda_margin"] else None,
                    "patMargin": float(row["pat_margin"]) if row["pat_margin"] else None,
                    "epsBasic": float(row["eps_basic"]) if row["eps_basic"] else None,
                    "prevYRevenue": float(row["prev_y_revenue"]) if row["prev_y_revenue"] else None,
                    "prevYPat": float(row["prev_y_pat"]) if row["prev_y_pat"] else None,
                    "prevYPbt": float(row["prev_y_pbt"]) if row["prev_y_pbt"] else None,
                    "prevQRevenue": float(row["prev_q_revenue"]) if row["prev_q_revenue"] else None,
                    "prevQPat": float(row["prev_q_pat"]) if row["prev_q_pat"] else None,
                    "prevQPbt": float(row["prev_q_pbt"]) if row["prev_q_pbt"] else None,
                    "revenueYoY": float(row["revenue_yoy"]) if row["revenue_yoy"] else None,
                    "patYoY": float(row["pat_yoy"]) if row["pat_yoy"] else None,
                    "pbtYoY": float(row["pbt_yoy"]) if row["pbt_yoy"] else None,
                    "revenueQoQ": float(row["revenue_qoq"]) if row["revenue_qoq"] else None,
                    "patQoQ": float(row["pat_qoq"]) if row["pat_qoq"] else None,
                    "pbtQoQ": float(row["pbt_qoq"]) if row["pbt_qoq"] else None,
                    "fetchedAt": str(row["fetched_at"]) if row["fetched_at"] else None
                })

            logger.info(f"Found {len(stocks)} stocks with good results")
            return {
                "stocks": stocks,
                "count": len(stocks),
                "filters": {
                    "minPatYoY": min_pat_yoy,
                    "minRevenueYoY": min_revenue_yoy,
                    "minPatQoQ": min_pat_qoq,
                    "minRevenueQoQ": min_revenue_qoq,
                    "minPbtYoY": min_pbt_yoy,
                    "minPbtQoQ": min_pbt_qoq,
                    "days": days,
                    "currentQuarterOnly": current_quarter_only,
                    "quarter": target_quarter,
                    "fiscalYear": target_fiscal_year,
                    "resultType": result_type
                }
            }

    except Exception as e:
        logger.error(f"Error getting good results: {e}", exc_info=True)
        return {"stocks": [], "error": str(e)}


@app.get("/api/results/compare")
async def compare_results(
    tickers: List[str] = Query(...),
    require_current_quarter: bool = Query(True, description="If true, fetch fresh data if cache doesn't have current quarter")
):
    """
    Get results for multiple tickers (portfolio comparison).
    Uses smart cache - fetches fresh data if current quarter results are missing.
    """
    try:
        results_by_ticker = {}

        for ticker in tickers:
            ticker = ticker.upper().strip()
            if ticker.startswith("NSE:"):
                ticker = ticker[4:]

            # Check cache first (with smart quarter check)
            cached = await db.get_cached_results(ticker, require_current_quarter=require_current_quarter)
            if cached:
                results_by_ticker[ticker] = cached
                continue

            # Cache miss/stale - fetch from Screener
            result = await screener_scraper.fetch_quarterly_results(ticker)
            if result.get("success") and result.get("results"):
                results = result["results"]
                results_by_ticker[ticker] = results
                # Save to cache
                await db.save_results(ticker, results)
            else:
                # Fallback to old cache if Screener fails
                old_cache = await db.get_cached_results(ticker, require_current_quarter=False)
                if old_cache:
                    results_by_ticker[ticker] = old_cache

        return {
            "resultsByTicker": results_by_ticker,
            "tickerCount": len(results_by_ticker)
        }

    except Exception as e:
        logger.error(f"Error comparing results: {e}", exc_info=True)
        return {"resultsByTicker": {}, "error": str(e)}


@app.get("/api/results/queue/status")
async def get_queue_status():
    """
    Get RabbitMQ queue status including DLQ message count.
    """
    try:
        if rabbitmq_consumer and rabbitmq_consumer.channel:
            # Declare queues with passive=True to get stats without modifying
            try:
                main_queue = await rabbitmq_consumer.channel.declare_queue(
                    "results.fetch.queue", durable=True, passive=True
                )
                main_count = main_queue.declaration_result.message_count if hasattr(main_queue, 'declaration_result') else 0
            except Exception:
                main_count = 0

            try:
                retry_queue = await rabbitmq_consumer.channel.declare_queue(
                    "results.fetch.retry", durable=True, passive=True
                )
                retry_count = retry_queue.declaration_result.message_count if hasattr(retry_queue, 'declaration_result') else 0
            except Exception:
                retry_count = 0

            try:
                dlq = await rabbitmq_consumer.channel.declare_queue(
                    "results.fetch.dlq", durable=True, passive=True
                )
                dlq_count = dlq.declaration_result.message_count if hasattr(dlq, 'declaration_result') else 0
            except Exception:
                dlq_count = 0

            return {
                "connected": True,
                "mainQueue": {
                    "name": "results.fetch.queue",
                    "messageCount": main_count
                },
                "retryQueue": {
                    "name": "results.fetch.retry",
                    "messageCount": retry_count
                },
                "deadLetterQueue": {
                    "name": "results.fetch.dlq",
                    "messageCount": dlq_count
                }
            }
        else:
            return {"connected": False, "message": "RabbitMQ consumer not running"}

    except Exception as e:
        logger.error(f"Error getting queue status: {e}")
        return {"connected": False, "error": str(e)}


@app.get("/api/results/queue/dlq/messages")
async def get_dlq_messages(limit: int = Query(50, description="Max messages to fetch")):
    """
    View messages in the Dead Letter Queue with their failure reasons.
    Messages are NOT consumed/removed - just peeked.
    """
    import json

    try:
        if not rabbitmq_consumer or not rabbitmq_consumer.channel:
            return {"error": "RabbitMQ consumer not connected"}

        # Declare queue to get message count
        dlq = await rabbitmq_consumer.channel.declare_queue(
            "results.fetch.dlq",
            durable=True,
            passive=True
        )

        message_count = dlq.declaration_result.message_count if hasattr(dlq, 'declaration_result') else 0
        messages = []
        collected_messages = []

        # Fetch messages (they are temporarily removed from queue)
        for _ in range(min(limit, message_count) if message_count else limit):
            try:
                message = await dlq.get(no_ack=False, timeout=1)
                if message is None:
                    break

                body = json.loads(message.body.decode())
                messages.append({
                    "ticker": body.get("ticker"),
                    "companyName": body.get("companyName"),
                    "announcementId": body.get("announcementId"),
                    "attemptNumber": body.get("attemptNumber"),
                    "maxAttempts": body.get("maxAttempts"),
                    "failureReason": body.get("failureReason"),
                    "lastFailReason": body.get("lastFailReason"),
                    "expectedQuarter": body.get("expectedQuarter"),
                    "failedAt": body.get("failedAt"),
                    "announcementTime": body.get("announcementTime"),
                    "subject": body.get("subject")
                })
                collected_messages.append(message)

            except asyncio.TimeoutError:
                break
            except Exception as e:
                logger.warning(f"Error fetching DLQ message: {e}")
                break

        # Reject all messages back to the queue
        for message in collected_messages:
            try:
                await message.reject(requeue=True)
            except Exception as e:
                logger.warning(f"Error rejecting message: {e}")

        return {
            "totalInDLQ": message_count if message_count else len(messages),
            "fetchedCount": len(messages),
            "messages": messages
        }

    except Exception as e:
        logger.error(f"Error getting DLQ messages: {e}")
        return {"error": str(e), "messages": []}


@app.post("/api/results/queue/dlq/reprocess")
async def reprocess_dlq_messages(
    limit: int = Query(50, description="Max messages to reprocess"),
    ticker_filter: Optional[str] = Query(None, description="Only reprocess specific ticker")
):
    """
    Move messages from DLQ back to main queue for reprocessing.
    Resets attempt counter so they get 5 fresh retries.
    """
    from aio_pika import Message, DeliveryMode
    import json

    try:
        if not rabbitmq_consumer or not rabbitmq_consumer.channel:
            return {"error": "RabbitMQ consumer not connected"}

        # Declare queue to access it
        dlq = await rabbitmq_consumer.channel.declare_queue(
            "results.fetch.dlq",
            durable=True,
            passive=True
        )

        reprocessed = []
        skipped = []
        errors = []

        processed = 0
        while len(reprocessed) < limit:
            try:
                message = await dlq.get(no_ack=False, timeout=1)
                if message is None:
                    break

                body = json.loads(message.body.decode())
                ticker = body.get("ticker", "")

                # Filter by ticker if specified
                if ticker_filter and ticker.upper() != ticker_filter.upper():
                    await message.reject(requeue=True)  # Keep in DLQ
                    skipped.append(ticker)
                    processed += 1
                    continue

                # Reset retry counter and remove failure metadata
                body["attemptNumber"] = 1
                body.pop("failureReason", None)
                body.pop("failedAt", None)
                body["reprocessedFromDLQ"] = True

                # Publish to main queue
                new_message = Message(
                    body=json.dumps(body).encode(),
                    delivery_mode=DeliveryMode.PERSISTENT,
                    content_type="application/json"
                )

                await rabbitmq_consumer.exchange.publish(
                    new_message,
                    routing_key="results.fetch"
                )

                # Acknowledge (remove from DLQ)
                await message.ack()
                reprocessed.append({
                    "ticker": ticker,
                    "companyName": body.get("companyName"),
                    "announcementId": body.get("announcementId")
                })
                processed += 1

            except Exception as e:
                logger.warning(f"Error reprocessing DLQ message: {e}")
                errors.append(str(e))
                processed += 1

        return {
            "reprocessedCount": len(reprocessed),
            "skippedCount": len(skipped),
            "errorCount": len(errors),
            "reprocessed": reprocessed,
            "errors": errors[:10] if errors else []
        }

    except Exception as e:
        logger.error(f"Error reprocessing DLQ: {e}")
        return {"error": str(e)}


@app.post("/api/results/queue/dlq/purge")
async def purge_dlq():
    """
    Purge all messages from the Dead Letter Queue.
    USE WITH CAUTION - messages will be permanently deleted.
    """
    try:
        if not rabbitmq_consumer or not rabbitmq_consumer.channel:
            return {"error": "RabbitMQ consumer not connected"}

        # Declare queue to access it
        dlq = await rabbitmq_consumer.channel.declare_queue(
            "results.fetch.dlq",
            durable=True,
            passive=True
        )

        message_count = dlq.declaration_result.message_count if hasattr(dlq, 'declaration_result') else 0
        await dlq.purge()

        return {
            "success": True,
            "purgedCount": message_count,
            "message": f"Purged {message_count} messages from DLQ"
        }

    except Exception as e:
        logger.error(f"Error purging DLQ: {e}")
        return {"error": str(e)}


@app.get("/api/results/queue/retry/messages")
async def get_retry_messages(limit: int = Query(100, description="Max messages to fetch")):
    """
    View messages in the Retry Queue.
    Messages are NOT consumed/removed - just peeked.
    """
    import json

    try:
        if not rabbitmq_consumer or not rabbitmq_consumer.channel:
            return {"error": "RabbitMQ consumer not connected"}

        # Declare queue to get message count
        retry_queue = await rabbitmq_consumer.channel.declare_queue(
            "results.fetch.retry",
            durable=True,
            passive=True
        )

        message_count = retry_queue.declaration_result.message_count if hasattr(retry_queue, 'declaration_result') else 0
        messages = []
        collected_messages = []

        # Fetch messages (they are temporarily removed from queue)
        for _ in range(min(limit, message_count) if message_count else limit):
            try:
                message = await retry_queue.get(no_ack=False, timeout=1)
                if message is None:
                    break

                body = json.loads(message.body.decode())
                messages.append({
                    "ticker": body.get("ticker"),
                    "companyName": body.get("companyName"),
                    "announcementId": body.get("announcementId"),
                    "attemptNumber": body.get("attemptNumber"),
                    "maxAttempts": body.get("maxAttempts"),
                    "retryScheduledAt": body.get("retryScheduledAt"),
                    "announcementTime": body.get("announcementTime"),
                    "subject": body.get("subject")
                })
                collected_messages.append(message)

            except asyncio.TimeoutError:
                break
            except Exception as e:
                logger.warning(f"Error fetching retry message: {e}")
                break

        # Reject all messages back to the queue
        for message in collected_messages:
            try:
                await message.reject(requeue=True)
            except Exception as e:
                logger.warning(f"Error rejecting message: {e}")

        return {
            "totalInRetry": message_count if message_count else len(messages),
            "fetchedCount": len(messages),
            "messages": messages
        }

    except Exception as e:
        logger.error(f"Error getting retry messages: {e}")
        return {"error": str(e), "messages": []}


@app.post("/api/results/queue/retry/reprocess")
async def reprocess_retry_queue(limit: int = Query(1000, description="Max messages to move")):
    """
    Move messages from retry queue back to main queue for immediate processing.
    Use this when you've fixed an issue and want to retry immediately instead of waiting 15 minutes.
    """
    from aio_pika import Message, DeliveryMode
    import json

    try:
        if not rabbitmq_consumer or not rabbitmq_consumer.channel:
            return {"error": "RabbitMQ consumer not connected"}

        # Declare retry queue
        retry_queue = await rabbitmq_consumer.channel.declare_queue(
            "results.fetch.retry",
            durable=True,
            passive=True
        )

        message_count = retry_queue.declaration_result.message_count if hasattr(retry_queue, 'declaration_result') else 0

        if message_count == 0:
            return {"message": "No messages in retry queue", "movedCount": 0}

        moved = 0
        errors = []

        for _ in range(min(limit, message_count)):
            try:
                message = await retry_queue.get(no_ack=False, timeout=1)
                if message is None:
                    break

                body = json.loads(message.body.decode())

                # Reset attempt counter for fresh start
                body["attemptNumber"] = 1
                body.pop("retryScheduledAt", None)
                body["reprocessedFromRetry"] = True

                # Publish to main queue
                new_message = Message(
                    body=json.dumps(body).encode(),
                    delivery_mode=DeliveryMode.PERSISTENT,
                    content_type="application/json"
                )

                await rabbitmq_consumer.exchange.publish(
                    new_message,
                    routing_key="results.fetch"
                )

                # Acknowledge (remove from retry queue)
                await message.ack()
                moved += 1

            except asyncio.TimeoutError:
                break
            except Exception as e:
                logger.warning(f"Error moving retry message: {e}")
                errors.append(str(e))

        return {
            "movedCount": moved,
            "errorCount": len(errors),
            "message": f"Moved {moved} messages from retry queue to main queue"
        }

    except Exception as e:
        logger.error(f"Error reprocessing retry queue: {e}")
        return {"error": str(e)}


@app.post("/api/results/queue/retry/purge")
async def purge_retry_queue():
    """
    Purge all messages from the Retry Queue.
    USE WITH CAUTION - messages will be permanently deleted.
    """
    try:
        if not rabbitmq_consumer or not rabbitmq_consumer.channel:
            return {"error": "RabbitMQ consumer not connected"}

        # Declare queue to access it
        retry_queue = await rabbitmq_consumer.channel.declare_queue(
            "results.fetch.retry",
            durable=True,
            passive=True
        )

        message_count = retry_queue.declaration_result.message_count if hasattr(retry_queue, 'declaration_result') else 0
        await retry_queue.purge()

        return {
            "success": True,
            "purgedCount": message_count,
            "message": f"Purged {message_count} messages from retry queue"
        }

    except Exception as e:
        logger.error(f"Error purging retry queue: {e}")
        return {"error": str(e)}


@app.post("/api/results/queue/retry/move-to-dlq")
async def move_retry_to_dlq(limit: int = Query(1000, description="Max messages to move")):
    """
    Move all messages from Retry Queue directly to DLQ.
    Useful after reprocessing to quickly capture failure reasons without waiting for retry delays.
    """
    from aio_pika import Message, DeliveryMode
    import json

    try:
        if not rabbitmq_consumer or not rabbitmq_consumer.channel:
            return {"error": "RabbitMQ consumer not connected"}

        retry_queue = await rabbitmq_consumer.channel.get_queue("results.fetch.retry")
        dlx_exchange = await rabbitmq_consumer.channel.get_exchange("results.dlx.exchange")

        moved = 0
        for _ in range(limit):
            try:
                message = await retry_queue.get(no_ack=False, timeout=5)
                if message is None:
                    break

                body = json.loads(message.body.decode())
                ticker = body.get("ticker", "UNKNOWN")
                last_reason = body.get("lastFailReason", "Unknown")

                # Set final failure reason with detail
                body["failureReason"] = f"Max retries exhausted: {last_reason}"
                body["failedAt"] = datetime.now().isoformat()

                dlq_message = Message(
                    body=json.dumps(body).encode(),
                    delivery_mode=DeliveryMode.PERSISTENT,
                    content_type="application/json"
                )
                await dlx_exchange.publish(dlq_message, routing_key="results.fetch.dead")
                await message.ack()
                moved += 1

            except asyncio.TimeoutError:
                break
            except Exception as e:
                logger.warning(f"Error moving retry message: {e}")
                break

        return {"movedToDLQ": moved}

    except Exception as e:
        logger.error(f"Error moving retry to DLQ: {e}")
        return {"error": str(e)}


@app.get("/api/results/queue/dlq/summary")
async def get_dlq_summary(max_messages: int = Query(500, description="Max messages to scan for summary")):
    """
    Get a summary of DLQ messages grouped by ticker and failure reason.
    """
    import json

    try:
        if not rabbitmq_consumer or not rabbitmq_consumer.channel:
            return {"error": "RabbitMQ consumer not connected"}

        # Declare queue to access it
        dlq = await rabbitmq_consumer.channel.declare_queue(
            "results.fetch.dlq",
            durable=True,
            passive=True
        )

        message_count = dlq.declaration_result.message_count if hasattr(dlq, 'declaration_result') else 0

        if message_count == 0:
            return {
                "totalInDLQ": 0,
                "uniqueTickers": 0,
                "byTicker": {},
                "byReason": {}
            }

        # Collect all messages first, then reject them back
        by_ticker = {}
        by_reason = {}
        collected_messages = []
        messages_to_scan = min(message_count, max_messages)

        # Fetch all messages (they are removed from queue temporarily)
        for _ in range(messages_to_scan):
            try:
                message = await dlq.get(no_ack=False, timeout=1)
                if message is None:
                    break

                body = json.loads(message.body.decode())
                ticker = body.get("ticker", "UNKNOWN")
                reason = body.get("failureReason", "Unknown reason")

                # Get the detailed reason (lastFailReason has the actual cause)
                last_fail = body.get("lastFailReason") or reason

                # Count by ticker
                if ticker not in by_ticker:
                    by_ticker[ticker] = {"count": 0, "companyName": body.get("companyName"), "lastFailReason": last_fail, "expectedQuarter": body.get("expectedQuarter")}
                by_ticker[ticker]["count"] += 1
                by_ticker[ticker]["lastFailReason"] = last_fail  # keep latest

                # Count by detailed reason
                if last_fail not in by_reason:
                    by_reason[last_fail] = 0
                by_reason[last_fail] += 1

                collected_messages.append(message)

            except asyncio.TimeoutError:
                break
            except Exception as e:
                logger.warning(f"Error reading DLQ message: {e}")
                break

        # Now reject all messages back to the queue
        for message in collected_messages:
            try:
                await message.reject(requeue=True)
            except Exception as e:
                logger.warning(f"Error rejecting message: {e}")

        # Sort by count
        by_ticker_sorted = dict(sorted(by_ticker.items(), key=lambda x: x[1]["count"], reverse=True))
        by_reason_sorted = dict(sorted(by_reason.items(), key=lambda x: x[1], reverse=True))

        return {
            "totalInDLQ": message_count,
            "uniqueTickers": len(by_ticker),
            "byTicker": by_ticker_sorted,
            "byReason": by_reason_sorted
        }

    except Exception as e:
        logger.error(f"Error getting DLQ summary: {e}")
        return {"error": str(e)}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8086)
