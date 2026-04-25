"""
Database module for quarterly results caching.
Uses PostgreSQL with asyncpg for async operations.
"""

import os
import logging
from typing import Dict, List, Any, Optional, Tuple
from datetime import datetime, timedelta

import asyncpg

logger = logging.getLogger(__name__)


def get_current_results_quarter() -> Tuple[str, int]:
    """
    Get the quarter whose results are currently being announced.

    Indian FY quarters:
    - Q1: Apr-Jun (results announced Jul-Aug)
    - Q2: Jul-Sep (results announced Oct-Nov)
    - Q3: Oct-Dec (results announced Jan-Feb)
    - Q4: Jan-Mar (results announced Apr-May)
    """
    now = datetime.now()
    month = now.month
    year = now.year

    if month in [1, 2]:  # Jan-Feb: Q3 results (Oct-Dec)
        return "Q3", year
    elif month in [4, 5]:  # Apr-May: Q4 results (Jan-Mar)
        return "Q4", year
    elif month in [7, 8]:  # Jul-Aug: Q1 results (Apr-Jun)
        return "Q1", year + 1
    elif month in [10, 11]:  # Oct-Nov: Q2 results (Jul-Sep)
        return "Q2", year + 1
    else:
        # In-between months - use previous quarter
        if month == 3:
            return "Q3", year
        elif month == 6:
            return "Q4", year
        elif month == 9:
            return "Q1", year + 1
        else:  # month == 12
            return "Q2", year + 1

# Database configuration from environment variables
DB_CONFIG = {
    "host": os.getenv("DB_HOST", "localhost"),
    "port": int(os.getenv("DB_PORT", "5432")),
    "database": os.getenv("DB_NAME", "portfolio_db"),
    "user": os.getenv("DB_USER", "postgres"),
    "password": os.getenv("DB_PASSWORD", "postgres"),
}

# Cache TTL in days - results older than this will be refreshed
CACHE_TTL_DAYS = int(os.getenv("RESULTS_CACHE_TTL_DAYS", "30"))


class ResultsDatabase:
    """Handles database operations for quarterly results caching."""

    def __init__(self):
        self.pool: Optional[asyncpg.Pool] = None

    async def connect(self):
        """Create database connection pool."""
        try:
            self.pool = await asyncpg.create_pool(
                host=DB_CONFIG["host"],
                port=DB_CONFIG["port"],
                database=DB_CONFIG["database"],
                user=DB_CONFIG["user"],
                password=DB_CONFIG["password"],
                min_size=2,
                max_size=10,
            )
            logger.info(f"Connected to PostgreSQL at {DB_CONFIG['host']}:{DB_CONFIG['port']}/{DB_CONFIG['database']}")

            # Ensure table exists
            await self._create_table_if_not_exists()
            return True

        except Exception as e:
            logger.error(f"Failed to connect to database: {e}")
            self.pool = None
            return False

    async def close(self):
        """Close database connection pool."""
        if self.pool:
            await self.pool.close()
            logger.info("Database connection closed")

    async def _create_table_if_not_exists(self):
        """Create the quarterly_results table if it doesn't exist."""
        if not self.pool:
            return

        create_sql = """
        CREATE TABLE IF NOT EXISTS quarterly_results (
            id SERIAL PRIMARY KEY,
            ticker VARCHAR(20) NOT NULL,
            quarter VARCHAR(10) NOT NULL,
            fiscal_year INT NOT NULL,
            quarter_label VARCHAR(30),
            result_type VARCHAR(20) DEFAULT 'consolidated',
            revenue DECIMAL(15,2),
            other_income DECIMAL(15,2),
            total_expenses DECIMAL(15,2),
            ebitda DECIMAL(15,2),
            ebitda_margin DECIMAL(8,2),
            pbt DECIMAL(15,2),
            tax DECIMAL(15,2),
            pat DECIMAL(15,2),
            pat_margin DECIMAL(8,2),
            eps_basic DECIMAL(10,2),
            source VARCHAR(20) DEFAULT 'screener',
            fetched_at TIMESTAMP DEFAULT NOW(),
            CONSTRAINT uq_ticker_quarter_type UNIQUE(ticker, quarter, fiscal_year, result_type)
        );

        CREATE INDEX IF NOT EXISTS idx_quarterly_results_ticker ON quarterly_results(ticker);
        CREATE INDEX IF NOT EXISTS idx_quarterly_results_fetched ON quarterly_results(fetched_at);
        CREATE INDEX IF NOT EXISTS idx_quarterly_results_type ON quarterly_results(result_type);
        """

        # Migration: Add result_type column if table already exists without it
        migration_sql = """
        DO $$
        BEGIN
            -- Add result_type column if it doesn't exist
            IF NOT EXISTS (
                SELECT 1 FROM information_schema.columns
                WHERE table_name = 'quarterly_results' AND column_name = 'result_type'
            ) THEN
                ALTER TABLE quarterly_results ADD COLUMN result_type VARCHAR(20) DEFAULT 'consolidated';
                CREATE INDEX IF NOT EXISTS idx_quarterly_results_type ON quarterly_results(result_type);
            END IF;

            -- Drop old constraint and add new one with result_type
            IF EXISTS (
                SELECT 1 FROM information_schema.table_constraints
                WHERE table_name = 'quarterly_results' AND constraint_name = 'uq_ticker_quarter'
            ) THEN
                ALTER TABLE quarterly_results DROP CONSTRAINT uq_ticker_quarter;
                ALTER TABLE quarterly_results ADD CONSTRAINT uq_ticker_quarter_type
                    UNIQUE(ticker, quarter, fiscal_year, result_type);
            END IF;
        END $$;
        """

        async with self.pool.acquire() as conn:
            await conn.execute(create_sql)
            await conn.execute(migration_sql)
            logger.info("Ensured quarterly_results table exists with result_type column")

    async def get_cached_results(
        self,
        ticker: str,
        require_current_quarter: bool = True,
        result_type: str = "consolidated"
    ) -> Optional[List[Dict[str, Any]]]:
        """
        Get cached results for a ticker.
        Returns None if:
        - No cache exists
        - Cache is stale (older than CACHE_TTL_DAYS)
        - require_current_quarter=True and cache doesn't have current quarter's results

        Args:
            ticker: Stock ticker symbol
            require_current_quarter: If True, cache is considered stale if it doesn't
                                     have the current results season quarter (e.g., Q3 FY26 in Jan-Feb)
            result_type: "consolidated", "standalone", or "both"
        """
        if not self.pool:
            logger.warning("Database not connected, skipping cache lookup")
            return None

        try:
            ticker = ticker.upper().strip()
            cutoff_date = datetime.now() - timedelta(days=CACHE_TTL_DAYS)

            async with self.pool.acquire() as conn:
                # Check if we have fresh data
                if result_type == "both":
                    rows = await conn.fetch(
                        """
                        SELECT * FROM quarterly_results
                        WHERE ticker = $1 AND fetched_at > $2
                        ORDER BY result_type, fiscal_year DESC, quarter DESC
                        """,
                        ticker, cutoff_date
                    )
                else:
                    rows = await conn.fetch(
                        """
                        SELECT * FROM quarterly_results
                        WHERE ticker = $1 AND fetched_at > $2
                            AND (result_type = $3 OR result_type IS NULL)
                        ORDER BY fiscal_year DESC, quarter DESC
                        """,
                        ticker, cutoff_date, result_type
                    )

                if not rows:
                    logger.info(f"No cached {result_type} results for {ticker} (or cache stale)")
                    return None

                # Check if cache has current quarter's results
                if require_current_quarter and rows:
                    current_q, current_fy = get_current_results_quarter()
                    latest_row = rows[0]
                    cached_q = latest_row["quarter"]
                    cached_fy = latest_row["fiscal_year"]

                    # Check if we have current quarter or newer
                    has_current = False
                    for row in rows:
                        if row["fiscal_year"] > current_fy:
                            has_current = True
                            break
                        elif row["fiscal_year"] == current_fy:
                            # Compare quarters: Q1 < Q2 < Q3 < Q4
                            q_order = {"Q1": 1, "Q2": 2, "Q3": 3, "Q4": 4}
                            if q_order.get(row["quarter"], 0) >= q_order.get(current_q, 0):
                                has_current = True
                                break

                    if not has_current:
                        logger.info(f"Cache for {ticker} is outdated: has {cached_q} FY{cached_fy}, "
                                    f"but current results season is {current_q} FY{current_fy}")
                        return None

                # Convert to list of dicts
                results = []
                for row in rows:
                    result_type_val = row.get("result_type", "consolidated") if "result_type" in row.keys() else "consolidated"
                    results.append({
                        "ticker": row["ticker"],
                        "quarter": row["quarter"],
                        "fiscalYear": row["fiscal_year"],
                        "quarterLabel": row["quarter_label"],
                        "resultType": result_type_val,
                        "revenue": float(row["revenue"]) if row["revenue"] else None,
                        "otherIncome": float(row["other_income"]) if row["other_income"] else None,
                        "totalExpenses": float(row["total_expenses"]) if row["total_expenses"] else None,
                        "ebitda": float(row["ebitda"]) if row["ebitda"] else None,
                        "ebitdaMargin": float(row["ebitda_margin"]) if row["ebitda_margin"] else None,
                        "pbt": float(row["pbt"]) if row["pbt"] else None,
                        "tax": float(row["tax"]) if row["tax"] else None,
                        "pat": float(row["pat"]) if row["pat"] else None,
                        "patMargin": float(row["pat_margin"]) if row["pat_margin"] else None,
                        "epsBasic": float(row["eps_basic"]) if row["eps_basic"] else None,
                        "source": "cache",
                    })

                logger.info(f"Found {len(results)} cached {result_type} results for {ticker}")
                return results

        except Exception as e:
            logger.error(f"Error fetching cached results: {e}")
            return None

    async def save_results(self, ticker: str, results: List[Dict[str, Any]]) -> bool:
        """
        Save quarterly results to database.
        Uses upsert to update existing records.
        Now includes result_type (consolidated/standalone).
        """
        if not self.pool:
            logger.warning("Database not connected, skipping cache save")
            return False

        if not results:
            return False

        try:
            ticker = ticker.upper().strip()

            async with self.pool.acquire() as conn:
                for result in results:
                    result_type = result.get("resultType", "consolidated")
                    await conn.execute(
                        """
                        INSERT INTO quarterly_results (
                            ticker, quarter, fiscal_year, quarter_label, result_type,
                            revenue, other_income, total_expenses,
                            ebitda, ebitda_margin, pbt, tax, pat, pat_margin,
                            eps_basic, source, fetched_at
                        ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, NOW())
                        ON CONFLICT (ticker, quarter, fiscal_year, result_type)
                        DO UPDATE SET
                            quarter_label = EXCLUDED.quarter_label,
                            revenue = EXCLUDED.revenue,
                            other_income = EXCLUDED.other_income,
                            total_expenses = EXCLUDED.total_expenses,
                            ebitda = EXCLUDED.ebitda,
                            ebitda_margin = EXCLUDED.ebitda_margin,
                            pbt = EXCLUDED.pbt,
                            tax = EXCLUDED.tax,
                            pat = EXCLUDED.pat,
                            pat_margin = EXCLUDED.pat_margin,
                            eps_basic = EXCLUDED.eps_basic,
                            source = EXCLUDED.source,
                            fetched_at = NOW()
                        """,
                        ticker,
                        result.get("quarter"),
                        result.get("fiscalYear"),
                        result.get("quarterLabel"),
                        result_type,
                        result.get("revenue"),
                        result.get("otherIncome"),
                        result.get("totalExpenses"),
                        result.get("ebitda"),
                        result.get("ebitdaMargin"),
                        result.get("pbt"),
                        result.get("tax"),
                        result.get("pat"),
                        result.get("patMargin"),
                        result.get("epsBasic"),
                        result.get("source", "screener"),
                    )

            result_types = set(r.get("resultType", "consolidated") for r in results)
            logger.info(f"Saved {len(results)} results for {ticker} ({', '.join(result_types)}) to cache")
            return True

        except Exception as e:
            logger.error(f"Error saving results to cache: {e}")
            return False

    async def invalidate_cache(self, ticker: str) -> bool:
        """Delete cached results for a ticker (force refresh)."""
        if not self.pool:
            return False

        try:
            ticker = ticker.upper().strip()
            async with self.pool.acquire() as conn:
                await conn.execute(
                    "DELETE FROM quarterly_results WHERE ticker = $1",
                    ticker
                )
            logger.info(f"Invalidated cache for {ticker}")
            return True

        except Exception as e:
            logger.error(f"Error invalidating cache: {e}")
            return False


# Global database instance
db = ResultsDatabase()
