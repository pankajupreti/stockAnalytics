"""
Screener.in Scraper - Fetches quarterly financial results from Screener.in.
This serves as a reliable fallback when PDF parsing fails (e.g., scanned PDFs).
"""

import re
import logging
from typing import Dict, List, Any, Optional
from datetime import datetime

import httpx

logger = logging.getLogger(__name__)


class ScreenerScraperService:
    """Scrapes quarterly results from Screener.in."""

    def __init__(self):
        self.base_url = "https://www.screener.in"
        self.timeout = 30
        self.headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.5",
        }

    async def fetch_quarterly_results(self, ticker: str, result_type: str = "auto") -> Dict[str, Any]:
        """
        Fetch quarterly results for a ticker from Screener.in.

        Args:
            ticker: Stock ticker symbol
            result_type: "consolidated", "standalone", or "auto" (tries consolidated first, falls back to standalone)

        Returns parsed results with metrics and result_type indicator.
        """
        try:
            # Normalize ticker
            ticker = ticker.upper().strip()
            if ticker.startswith("NSE:"):
                ticker = ticker[4:]

            logger.info(f"Fetching Screener data for: {ticker} (type={result_type})")

            async with httpx.AsyncClient(timeout=self.timeout) as client:
                results = []
                actual_type = None

                if result_type in ("consolidated", "auto"):
                    # Try consolidated
                    url = f"{self.base_url}/company/{ticker}/consolidated/"
                    response = await client.get(url, headers=self.headers, follow_redirects=True)

                    if response.status_code == 200:
                        html = response.text
                        results = self._parse_quarterly_table(html, ticker)
                        if results:
                            actual_type = "consolidated"
                            logger.info(f"Found {len(results)} quarters from consolidated")

                if not results and result_type in ("standalone", "auto"):
                    # Try standalone
                    logger.info(f"Trying standalone for {ticker}...")
                    url = f"{self.base_url}/company/{ticker}/"
                    response = await client.get(url, headers=self.headers, follow_redirects=True)

                    if response.status_code == 200:
                        html = response.text
                        results = self._parse_quarterly_table(html, ticker)
                        if results:
                            actual_type = "standalone"
                            logger.info(f"Found {len(results)} quarters from standalone")

                if response.status_code != 200:
                    logger.warning(f"Screener returned {response.status_code} for {ticker}")
                    return {"success": False, "error": f"HTTP {response.status_code}"}

            if not results:
                return {"success": False, "error": "Could not parse quarterly results table"}

            # Add result_type to each result
            for r in results:
                r["resultType"] = actual_type

            return {
                "success": True,
                "ticker": ticker,
                "results": results,
                "resultType": actual_type,
                "source": "screener"
            }

        except Exception as e:
            logger.error(f"Error fetching from Screener: {e}", exc_info=True)
            return {"success": False, "error": str(e)}

    async def fetch_both_result_types(self, ticker: str) -> Dict[str, Any]:
        """
        Fetch BOTH consolidated and standalone results for a ticker.
        Returns both sets of data for comparison or storage.
        """
        try:
            ticker = ticker.upper().strip()
            if ticker.startswith("NSE:"):
                ticker = ticker[4:]

            logger.info(f"Fetching both result types for: {ticker}")

            consolidated_results = []
            standalone_results = []

            async with httpx.AsyncClient(timeout=self.timeout) as client:
                # Fetch consolidated
                url = f"{self.base_url}/company/{ticker}/consolidated/"
                response = await client.get(url, headers=self.headers, follow_redirects=True)

                if response.status_code == 200:
                    consolidated_results = self._parse_quarterly_table(response.text, ticker)
                    for r in consolidated_results:
                        r["resultType"] = "consolidated"
                    logger.info(f"Found {len(consolidated_results)} consolidated quarters")

                # Fetch standalone
                url = f"{self.base_url}/company/{ticker}/"
                response = await client.get(url, headers=self.headers, follow_redirects=True)

                if response.status_code == 200:
                    standalone_results = self._parse_quarterly_table(response.text, ticker)
                    for r in standalone_results:
                        r["resultType"] = "standalone"
                    logger.info(f"Found {len(standalone_results)} standalone quarters")

            has_consolidated = len(consolidated_results) > 0
            has_standalone = len(standalone_results) > 0

            if not has_consolidated and not has_standalone:
                return {"success": False, "error": "No results found (neither consolidated nor standalone)"}

            return {
                "success": True,
                "ticker": ticker,
                "hasConsolidated": has_consolidated,
                "hasStandalone": has_standalone,
                "consolidated": consolidated_results,
                "standalone": standalone_results,
                "source": "screener"
            }

        except Exception as e:
            logger.error(f"Error fetching both result types: {e}", exc_info=True)
            return {"success": False, "error": str(e)}

    def _parse_quarterly_table(self, html: str, ticker: str) -> List[Dict[str, Any]]:
        """
        Parse the quarterly results table from Screener HTML.
        Returns list of quarterly results.
        """
        results = []

        try:
            # Find the data-result-table div with quarterly data
            table_match = re.search(
                r'<div[^>]*data-result-table[^>]*>\s*<table[^>]*class="data-table[^"]*"[^>]*>(.*?)</table>',
                html, re.DOTALL
            )

            if not table_match:
                logger.warning("Could not find quarterly results table")
                return []

            table_html = table_match.group(1)

            # Extract headers (quarter dates like "Dec 2024")
            headers = []
            thead_match = re.search(r'<thead>(.*?)</thead>', table_html, re.DOTALL)
            if thead_match:
                thead_html = thead_match.group(1)
                # Match month year patterns in th tags
                th_pattern = r'<th[^>]*>\s*((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+\d{4})'
                headers = re.findall(th_pattern, thead_html, re.IGNORECASE)

            if not headers:
                logger.warning("Could not extract quarter headers")
                return []

            logger.info(f"Found {len(headers)} quarters: {headers[:4]}...")

            # Extract rows from tbody
            tbody_match = re.search(r'<tbody>(.*?)</tbody>', table_html, re.DOTALL)
            if not tbody_match:
                return []

            tbody = tbody_match.group(1)

            # Parse each row
            row_pattern = r'<tr[^>]*>(.*?)</tr>'
            rows = re.findall(row_pattern, tbody, re.DOTALL)

            # Metric mapping
            metric_map = {
                "sales": "revenue",
                "revenue from operations": "revenue",
                "expenses": "total_expenses",
                "operating profit": "ebitda",
                "opm": "ebitda_margin",
                "other income": "other_income",
                "interest": "interest",
                "depreciation": "depreciation",
                "profit before tax": "pbt",
                "tax": "tax",
                "net profit": "pat",
                "eps in rs": "eps_basic",
            }

            # Initialize metrics by quarter
            metrics_by_quarter = {q: {} for q in headers}

            for row_html in rows:
                # Get row label from first td - can be in <button> or directly in td
                # Pattern matches: Sales&nbsp; or just Sales or Net Profit etc.
                label_match = re.search(
                    r'<td[^>]*class="text"[^>]*>.*?(?:<button[^>]*>)?\s*([A-Za-z][A-Za-z\s%]+)',
                    row_html, re.DOTALL
                )
                if not label_match:
                    continue

                label = label_match.group(1).strip().lower()
                # Remove &nbsp; and similar
                label = re.sub(r'&nbsp;.*', '', label).strip()

                # Find matching metric
                metric_key = None
                for pattern, key in metric_map.items():
                    if pattern in label:
                        metric_key = key
                        break

                if not metric_key:
                    continue

                # Extract all td values (skip first text column)
                tds = re.findall(r'<td[^>]*>(.*?)</td>', row_html, re.DOTALL)

                # Skip the first (label) column
                values = []
                for td in tds[1:]:
                    # Clean HTML - remove all tags
                    clean = re.sub(r'<[^>]+>', '', td)
                    clean = clean.replace('&nbsp;', ' ').strip()
                    value = self._parse_cell_value(clean)
                    values.append(value)

                # Map values to quarters
                for i, header in enumerate(headers):
                    if i < len(values) and values[i] is not None:
                        metrics_by_quarter[header][metric_key] = values[i]

            # Build result objects (most recent first)
            for quarter_label in reversed(headers):
                metrics = metrics_by_quarter.get(quarter_label, {})
                if not metrics:
                    continue

                quarter, fiscal_year = self._parse_quarter_label(quarter_label)

                result = {
                    "ticker": ticker,
                    "quarter": quarter,
                    "fiscalYear": fiscal_year,
                    "quarterLabel": f"{quarter} FY{fiscal_year}",
                    "revenue": metrics.get("revenue"),
                    "otherIncome": metrics.get("other_income"),
                    "totalExpenses": metrics.get("total_expenses"),
                    "ebitda": metrics.get("ebitda"),
                    "ebitdaMargin": metrics.get("ebitda_margin"),
                    "pbt": metrics.get("pbt"),
                    "tax": metrics.get("tax"),
                    "pat": metrics.get("pat"),
                    "epsBasic": metrics.get("eps_basic"),
                    "source": "screener"
                }

                # Calculate PAT margin
                if result["pat"] and result["revenue"] and result["revenue"] != 0:
                    result["patMargin"] = round((result["pat"] / result["revenue"]) * 100, 2)

                results.append(result)

            logger.info(f"Parsed {len(results)} quarterly results from Screener")
            return results

        except Exception as e:
            logger.error(f"Error parsing Screener HTML: {e}", exc_info=True)
            return []

    def _extract_section(self, html: str, section_id: str) -> Optional[str]:
        """Extract a section from Screener HTML by ID."""
        # Look for section with id="quarters"
        pattern = rf'<section[^>]*id="{section_id}"[^>]*>(.*?)</section>'
        match = re.search(pattern, html, re.DOTALL | re.IGNORECASE)
        if match:
            return match.group(1)

        # Try finding by data-result-table
        pattern = r'<table[^>]*class="[^"]*data-table[^"]*"[^>]*>(.*?)</table>'
        tables = re.findall(pattern, html, re.DOTALL | re.IGNORECASE)

        # Find the table with quarterly data
        for table in tables:
            if "Sales" in table or "Net Profit" in table:
                return table

        return None

    def _extract_headers(self, section_html: str) -> List[str]:
        """Extract column headers (quarter labels) from table."""
        headers = []

        # Look for header row
        thead_match = re.search(r'<thead[^>]*>(.*?)</thead>', section_html, re.DOTALL)
        if thead_match:
            thead = thead_match.group(1)
            # Extract th elements
            th_pattern = r'<th[^>]*>(.*?)</th>'
            ths = re.findall(th_pattern, thead, re.DOTALL)

            for th in ths[1:]:  # Skip first (label column)
                # Clean HTML tags
                clean = re.sub(r'<[^>]+>', '', th).strip()
                if clean and re.search(r'(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)', clean):
                    headers.append(clean)

        return headers[:8]  # Limit to 8 quarters

    def _extract_row_values(self, section_html: str, row_label: str, expected_count: int) -> List[Optional[float]]:
        """Extract values from a specific row."""
        values = []

        # Find the row containing the label
        # Pattern: <td>Row Label</td> followed by value cells
        row_pattern = rf'<tr[^>]*>.*?<td[^>]*>[^<]*{re.escape(row_label)}[^<]*</td>(.*?)</tr>'
        row_match = re.search(row_pattern, section_html, re.DOTALL | re.IGNORECASE)

        if not row_match:
            return values

        row_content = row_match.group(1)

        # Extract td values
        td_pattern = r'<td[^>]*>(.*?)</td>'
        tds = re.findall(td_pattern, row_content, re.DOTALL)

        for td in tds[:expected_count]:
            value = self._parse_cell_value(td)
            values.append(value)

        return values

    def _parse_cell_value(self, cell_html: str) -> Optional[float]:
        """Parse a numeric value from a table cell."""
        # Remove HTML tags
        text = re.sub(r'<[^>]+>', '', cell_html).strip()

        if not text or text == '-' or text == '':
            return None

        # Handle negative in parentheses
        is_negative = text.startswith('(') and text.endswith(')')
        if is_negative:
            text = text[1:-1]

        # Handle percentage
        is_percent = text.endswith('%')
        if is_percent:
            text = text[:-1]

        # Remove commas
        text = text.replace(',', '')

        try:
            value = float(text)
            return -value if is_negative else value
        except ValueError:
            return None

    def _parse_quarter_label(self, label: str) -> tuple:
        """
        Parse quarter label like 'Dec 2025' into (quarter, fiscal_year).
        Indian fiscal year: Apr-Mar
        """
        # Default
        quarter = "Q3"
        fiscal_year = 2026

        # Parse month and year
        match = re.search(r'(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s*(\d{4})', label)
        if match:
            month_name = match.group(1)
            year = int(match.group(2))

            month_map = {
                "Jan": 1, "Feb": 2, "Mar": 3, "Apr": 4, "May": 5, "Jun": 6,
                "Jul": 7, "Aug": 8, "Sep": 9, "Oct": 10, "Nov": 11, "Dec": 12
            }
            month = month_map.get(month_name, 12)

            # Determine quarter
            if 1 <= month <= 3:
                quarter = "Q4"
                fiscal_year = year
            elif 4 <= month <= 6:
                quarter = "Q1"
                fiscal_year = year + 1
            elif 7 <= month <= 9:
                quarter = "Q2"
                fiscal_year = year + 1
            else:  # Oct-Dec
                quarter = "Q3"
                fiscal_year = year + 1

        return quarter, fiscal_year


# Synchronous wrapper for testing
def fetch_quarterly_results_sync(ticker: str) -> Dict[str, Any]:
    """Synchronous version for testing."""
    import asyncio
    scraper = ScreenerScraperService()
    return asyncio.run(scraper.fetch_quarterly_results(ticker))
