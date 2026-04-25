-- Quarterly Results Cache Table
-- Stores fetched quarterly results to avoid repeated API calls to Screener.in

CREATE TABLE IF NOT EXISTS quarterly_results (
    id SERIAL PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    quarter VARCHAR(10) NOT NULL,           -- Q1, Q2, Q3, Q4
    fiscal_year INT NOT NULL,
    quarter_label VARCHAR(30),              -- e.g., "Q3 FY2026"

    -- Financial metrics (in Crores)
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

    -- Metadata
    source VARCHAR(20) DEFAULT 'screener',  -- 'screener' or 'pdf'
    fetched_at TIMESTAMP DEFAULT NOW(),

    -- Unique constraint: one record per ticker/quarter/year
    CONSTRAINT uq_ticker_quarter UNIQUE(ticker, quarter, fiscal_year)
);

-- Index for fast lookups by ticker
CREATE INDEX IF NOT EXISTS idx_quarterly_results_ticker ON quarterly_results(ticker);

-- Index for finding stale data
CREATE INDEX IF NOT EXISTS idx_quarterly_results_fetched ON quarterly_results(fetched_at);

-- Comments for documentation
COMMENT ON TABLE quarterly_results IS 'Cache for quarterly financial results fetched from Screener.in';
COMMENT ON COLUMN quarterly_results.fetched_at IS 'When data was fetched - used for cache invalidation';
