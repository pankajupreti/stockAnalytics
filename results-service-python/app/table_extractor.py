"""
Table Extractor for Indian Quarterly Financial Results.
Parses standard SEBI format quarterly result PDFs.

PDF Structure (typical Indian quarterly results):
- Table with columns: Particulars | Quarter ended (multiple) | Nine months ended | Year ended
- Column headers have dates like 31.12.2025, 30.09.2025, 31.12.2024
- Rows: Revenue from operations, Other Income, Total Income, Total Expenses,
        Profit before tax, Tax expense, Profit after tax, EPS Basic, EPS Diluted

Handles both clean text PDFs and noisy OCR output.
"""

import re
import logging
from typing import Dict, List, Any, Optional, Tuple
from datetime import datetime

import pdfplumber

logger = logging.getLogger(__name__)


class OCRTextProcessor:
    """
    Specialized processor for extracting metrics from noisy OCR text.
    Designed for Indian quarterly financial results PDFs.
    Uses pattern matching with context awareness.
    """

    def __init__(self):
        # Patterns that work even with OCR noise
        # Match label followed by numbers on the same line
        # Patterns for extracting metrics from consolidated results
        # These are designed to work with OCR text that may have noise
        # The capture group should be the value we want (first numeric column = current quarter)
        self.metric_patterns = {
            "revenue": [
                r"revenue\s+from\s+operations?\s+([0-9,.]+)",
                r"\(a\)\s*revenue[^\d]*([0-9,.]+)",
            ],
            "other_income": [
                r"other\s*income[^\d]*([0-9,.]+)",
                r"\(b\)\s*other\s*income[^\d]*([0-9,.]+)",
            ],
            "total_income": [
                r"total\s*income[^\d]*([0-9,.]+)",
            ],
            "total_expenses": [
                r"total\s*expenses[^\d]*([0-9,.]+)",
                r"total\s*expenditure[^\d]*([0-9,.]+)",
            ],
            "pbt": [
                # Look for patterns in consolidated section
                # "Profit before exceptional items, tax..." or "controlled entities (1-2)" followed by numbers
                r"entities\s*\(1-2\)\s*([0-9,.]+)",
                r"controlled[\s\-]*entities\s*\(1-2\)[^\d]*([0-9,.]+)",
                r"profit\s+before\s+exceptional[^\d]*([0-9,.]+)",
            ],
            "pat": [
                # In consolidated: look for profit for period/year (7+8) or after specific markers
                r"period[/\s]*year\s*\(7\+8\)[^\d]*([0-9,.]+)",
                r"period[/\s]*year\s*\(?7\+8\)?[^\d]*([0-9,.]+)",
                r"net\s+profit\s+for\s+the\s+period[^\d]*([0-9,.]+)",
            ],
            "eps_basic": [
                # EPS patterns - look for "basic (rs)" or "Basie RS" (OCR variant)
                r"basi[ce]\s*\(?\s*rs\.?\s*\)?[^\d]*([0-9,.]+)",
                r"basi[ce]\s+rs[^\d]*([0-9,.]+)",
            ],
        }

    def extract_from_ocr_text(self, text: str) -> Dict[str, Optional[float]]:
        """
        Extract metrics from OCR text using flexible pattern matching.
        Prioritizes consolidated results over standalone.
        """
        metrics = {}

        # Try to extract from consolidated financial results section
        # Look for the actual consolidated table, not just any mention of "consolidated"
        consolidated_markers = [
            "statement of unaudited consolidated",
            "consolidated financial results",
            "unaudited consolidated financial",
        ]

        text_lower = text.lower()
        for marker in consolidated_markers:
            consolidated_start = text_lower.find(marker)
            if consolidated_start > 0:
                # Use text from this section onwards (skip cover letter and standalone)
                text = text[consolidated_start:]
                logger.debug(f"Found consolidated section with '{marker}' at position {consolidated_start}")
                break

        # Clean OCR artifacts but preserve structure
        clean_text = re.sub(r'[|_\[\]{}�]', ' ', text)
        clean_text = re.sub(r'\s+', ' ', clean_text)
        clean_lower = clean_text.lower()

        for metric_name, patterns in self.metric_patterns.items():
            if metric_name in metrics:
                continue

            for pattern in patterns:
                match = re.search(pattern, clean_lower)
                if match:
                    # Get the last group (the number)
                    num_str = match.group(match.lastindex)
                    value = self._parse_number(num_str)
                    if value and self._is_reasonable_value(metric_name, value):
                        metrics[metric_name] = value
                        logger.debug(f"OCR: Found {metric_name}={value} with pattern '{pattern[:30]}...'")
                        break

        # If we couldn't find PAT with pattern, try the line-based search
        if "pat" not in metrics:
            pat = self._find_pat_in_lines(text)
            if pat:
                metrics["pat"] = pat
                logger.debug(f"OCR: Found PAT={pat} via line search")

        # If we couldn't find EPS with pattern, try specific search
        if "eps_basic" not in metrics:
            eps = self._find_eps_in_text(text)
            if eps:
                metrics["eps_basic"] = eps
                logger.debug(f"OCR: Found EPS={eps} via line search")

        # Calculate EBITDA if possible
        if "ebitda" not in metrics and "pbt" in metrics:
            if "tax" in metrics:
                metrics["ebitda"] = round(metrics["pbt"] + abs(metrics.get("tax", 0)), 2)

        logger.info(f"OCR extraction found: {list(metrics.keys())}")
        return metrics

    def _parse_number(self, num_str: str) -> Optional[float]:
        """Parse a number string, handling Indian format."""
        if not num_str:
            return None

        # Remove commas and spaces
        clean = num_str.replace(',', '').replace(' ', '').strip()

        # Skip if looks like a date
        if re.match(r'^\d{1,2}\.\d{1,2}$', clean):
            return None

        # Skip year-like numbers
        if re.match(r'^20[2-3]\d$', clean):
            return None

        try:
            return float(clean)
        except ValueError:
            return None

    def _is_reasonable_value(self, metric_name: str, value: float) -> bool:
        """Check if a value is reasonable for a given metric."""
        abs_val = abs(value)

        if metric_name == "eps_basic":
            return 0.01 <= abs_val <= 500
        elif metric_name in ["revenue", "total_income", "total_expenses"]:
            return 10 <= abs_val <= 50000
        elif metric_name in ["pbt", "pat"]:
            return 1 <= abs_val <= 10000
        elif metric_name == "other_income":
            return 0.1 <= abs_val <= 5000
        elif metric_name == "tax":
            return 0.1 <= abs_val <= 5000

        return True

    def _find_pat_in_lines(self, text: str) -> Optional[float]:
        """
        Find PAT value using line-by-line search.
        Look for patterns like:
        - "Profit for the period/year (7+8)" followed by numbers
        - Numbers on lines right after profit label
        """
        lines = text.split('\n')

        for i, line in enumerate(lines):
            line_lower = line.lower()

            # Check if this line has the profit label
            if "profit for the period" in line_lower or ("profit" in line_lower and "(7+8)" in line):
                # Extract numbers from this line and next few lines
                combined = line
                for j in range(i + 1, min(i + 4, len(lines))):
                    combined += " " + lines[j]

                # Clean and find numbers
                clean = re.sub(r'[|_\[\]{}�()]', ' ', combined)
                numbers = re.findall(r'([0-9]+\.[0-9]+)', clean)

                for num_str in numbers:
                    value = self._parse_number(num_str)
                    if value and 1 <= value <= 10000:
                        return value

        return None

    def _find_eps_in_text(self, text: str) -> Optional[float]:
        """
        Find EPS value - typically small numbers (< 100) near 'basic' or 'diluted'.
        """
        lines = text.split('\n')

        for i, line in enumerate(lines):
            line_lower = line.lower()

            if 'basic' in line_lower and ('rs' in line_lower or 'eps' in line_lower or 'earning' in line_lower):
                # Look for small numbers
                clean = re.sub(r'[|_\[\]{}�]', ' ', line)
                numbers = re.findall(r'([0-9]+\.[0-9]+)', clean)

                for num_str in numbers:
                    value = self._parse_number(num_str)
                    if value and 0.01 <= value <= 100:  # EPS is typically small
                        return value

        return None


class QuarterlyResultsExtractor:
    """Extracts financial metrics from quarterly result PDFs."""

    def __init__(self):
        # Patterns for finding metrics in text
        self.metric_patterns = {
            "revenue": [
                r"\(a\)\s*revenue\s+from\s+operations",
                r"revenue\s+from\s+operations",
                r"net\s+sales",
                r"total\s+revenue\s+from\s+operations"
            ],
            "other_income": [
                r"\(b\)\s*other\s+income",
                r"other\s+income"
            ],
            "total_income": [
                r"total\s+income\s+\(1\+2\)",
                r"total\s+income",
                r"total\s+revenue"
            ],
            "total_expenses": [
                r"total\s+expenses",
                r"total\s+expenditure"
            ],
            "pbt": [
                r"profit\s+before\s+tax\s*\(3[+-]4\)",
                r"profit\s+before\s+tax",
                r"profit/\(loss\)\s+before\s+tax",
                r"profit\s+before\s+exceptional"
            ],
            "tax": [
                r"tax\s+expense",
                r"tax\s+expenses",
                r"income\s+tax\s+expense"
            ],
            "pat": [
                r"profit\s+for\s+the\s+period/year",
                r"profit\s+for\s+the\s+period",
                r"net\s+profit\s+after\s+tax",
                r"profit/\(loss\)\s+for\s+the\s+period",
                r"profit\s+after\s+tax"
            ],
            "eps_basic": [
                r"-\s*basic\s*\(rs\.?\)",
                r"-\s*basic",
                r"basic\s+eps",
                r"earnings\s+per\s+share.*basic"
            ],
            "eps_diluted": [
                r"-\s*diluted\s*\(rs\.?\)",
                r"-\s*diluted",
                r"diluted\s+eps",
                r"earnings\s+per\s+share.*diluted"
            ],
            # Bank specific
            "nii": [
                r"net\s+interest\s+income",
                r"interest\s+earned\s*-\s*interest\s+expended"
            ],
            "provisions": [
                r"provisions\s+and\s+contingencies",
                r"provision\s+for\s+npa"
            ]
        }

        # Exclusion patterns to avoid wrong matches
        self.exclusions = {
            "revenue": ["total income", "comprehensive"],
            "other_income": ["comprehensive"],
            "total_income": ["comprehensive"],
            "pat": ["share of profit", "attributable", "comprehensive", "before"],
            "pbt": ["share of profit", "attributable"]
        }

    def extract_metrics(self, text: str, is_ocr: bool = False) -> Dict[str, Optional[float]]:
        """
        Extract financial metrics from PDF text.
        Returns dict with metric names as keys and values.

        Args:
            text: The text extracted from PDF
            is_ocr: If True, use OCR-specific extraction logic
        """
        if not text:
            return {}

        # If OCR text, use the specialized OCR processor
        if is_ocr or self._looks_like_ocr(text):
            logger.info("Using OCR-specific extraction")
            ocr_processor = OCRTextProcessor()
            metrics = ocr_processor.extract_from_ocr_text(text)
            if metrics:
                return metrics
            # Fall through to standard extraction if OCR processor finds nothing

        metrics = {}
        lines = text.split('\n')

        for metric_name, patterns in self.metric_patterns.items():
            value = self._find_metric_value(lines, patterns, self.exclusions.get(metric_name, []))
            if value is not None:
                metrics[metric_name] = value
                logger.debug(f"Found {metric_name}: {value}")

        # Calculate EBITDA if not found directly
        if "ebitda" not in metrics:
            if metrics.get("total_income") and metrics.get("total_expenses"):
                metrics["ebitda"] = metrics["total_income"] - metrics["total_expenses"]

        logger.info(f"Extracted metrics: {list(metrics.keys())}")
        return metrics

    def _looks_like_ocr(self, text: str) -> bool:
        """
        Detect if text appears to be from OCR (noisy).
        OCR text typically has: pipe characters, scattered single chars, OCR artifacts
        """
        if not text:
            return False

        # Count OCR artifacts
        artifacts = text.count('|') + text.count('�') + text.count('_')
        # Count lines that are just 1-2 characters (OCR fragments)
        lines = text.split('\n')
        short_lines = sum(1 for line in lines if 0 < len(line.strip()) <= 2)

        # If more than 5% artifacts or 10% short lines, likely OCR
        artifact_ratio = artifacts / max(len(text), 1)
        short_line_ratio = short_lines / max(len(lines), 1)

        is_ocr = artifact_ratio > 0.01 or short_line_ratio > 0.1
        if is_ocr:
            logger.debug(f"Text looks like OCR: artifacts={artifact_ratio:.2%}, short_lines={short_line_ratio:.2%}")
        return is_ocr

    def _find_metric_value(
        self,
        lines: List[str],
        patterns: List[str],
        exclusions: List[str]
    ) -> Optional[float]:
        """Find a metric value in the text lines."""
        for i, line in enumerate(lines):
            line_lower = line.lower()

            # Check if line matches any pattern
            matched = False
            for pattern in patterns:
                if re.search(pattern, line_lower):
                    matched = True
                    break

            if not matched:
                continue

            # Check exclusions
            excluded = False
            for excl in exclusions:
                if excl.lower() in line_lower:
                    excluded = True
                    break

            if excluded:
                continue

            # Extract numbers from this line
            numbers = self._extract_numbers(line)

            # If not enough numbers, try combining with next lines
            if len(numbers) < 1 and i + 1 < len(lines):
                combined = line + " " + lines[i + 1]
                numbers = self._extract_numbers(combined)

            if len(numbers) < 1 and i + 2 < len(lines):
                combined = line + " " + lines[i + 1] + " " + lines[i + 2]
                numbers = self._extract_numbers(combined)

            if numbers:
                # Return first number (current quarter)
                return numbers[0]

        return None

    def _extract_numbers(self, text: str) -> List[float]:
        """
        Extract numeric values from text.
        Handles Indian number format (1,23,456.78), negative in parentheses, etc.
        """
        numbers = []

        # Normalize whitespace
        text = re.sub(r'\s+', ' ', text)

        # Pattern for numbers:
        # - Parentheses for negative: (123.45)
        # - Indian format: 1,23,456.78
        # - Simple decimal: 123.45
        # - Integer: 12345
        pattern = r'''
            \(([0-9,]+\.?[0-9]*)\)   |  # Negative in parens
            (?<![0-9])([0-9]{1,3}(?:,[0-9]{2,3})*\.[0-9]+)  |  # Indian decimal
            (?<![0-9])([0-9]+\.[0-9]+)  |  # Simple decimal
            (?<![0-9.])([0-9,]{3,})(?![0-9.])  # Integer with commas
        '''

        for match in re.finditer(pattern, text, re.VERBOSE):
            num_str = None
            is_negative = False

            if match.group(1):  # Negative in parens
                num_str = match.group(1)
                is_negative = True
            elif match.group(2):  # Indian decimal
                num_str = match.group(2)
            elif match.group(3):  # Simple decimal
                num_str = match.group(3)
            elif match.group(4):  # Integer
                num_str = match.group(4)

            if num_str:
                # Skip year-like numbers (2020-2030)
                clean = num_str.replace(',', '')
                if re.match(r'^20[2-3]\d$', clean) and '.' not in num_str:
                    continue

                # Skip date-like patterns (31.12)
                if re.match(r'^\d{1,2}\.\d{1,2}$', num_str):
                    continue

                # Skip single digits
                if len(clean) == 1:
                    continue

                try:
                    value = float(clean)
                    if is_negative:
                        value = -value
                    if value != 0:
                        numbers.append(value)
                except ValueError:
                    pass

        return numbers

    def extract_quarter_info(self, text: str) -> Dict[str, Any]:
        """
        Extract quarter and fiscal year from PDF text.
        Returns dict with 'quarter' (Q1-Q4) and 'fiscal_year'.
        """
        if not text:
            return {"quarter": "Q3", "fiscal_year": 2026}

        # Pattern 1: DD.MM.YYYY or DD/MM/YYYY
        date_match = re.search(r'(\d{1,2})[./](\d{1,2})[./](\d{4})', text)
        if date_match:
            day = int(date_match.group(1))
            month = int(date_match.group(2))
            year = int(date_match.group(3))

            quarter = self._month_to_quarter(month)
            fiscal_year = self._month_to_fiscal_year(month, year)

            logger.info(f"Extracted quarter from date {day}/{month}/{year}: {quarter} FY{fiscal_year}")
            return {"quarter": quarter, "fiscal_year": fiscal_year}

        # Pattern 2: Month name + Year
        month_match = re.search(
            r'(January|February|March|April|May|June|July|August|September|October|November|December)\s*,?\s*(\d{4})',
            text, re.IGNORECASE
        )
        if month_match:
            month_name = month_match.group(1).lower()
            year = int(month_match.group(2))
            month = self._month_name_to_number(month_name)

            quarter = self._month_to_quarter(month)
            fiscal_year = self._month_to_fiscal_year(month, year)

            logger.info(f"Extracted quarter from {month_name} {year}: {quarter} FY{fiscal_year}")
            return {"quarter": quarter, "fiscal_year": fiscal_year}

        # Pattern 3: Q3 FY26 or Q3FY2026
        q_match = re.search(r'Q([1-4])\s*FY\s*(\d{2,4})', text, re.IGNORECASE)
        if q_match:
            quarter = f"Q{q_match.group(1)}"
            year_str = q_match.group(2)
            if len(year_str) == 2:
                year_str = "20" + year_str
            return {"quarter": quarter, "fiscal_year": int(year_str)}

        logger.warning("Could not extract quarter info, using default Q3 2026")
        return {"quarter": "Q3", "fiscal_year": 2026}

    def _month_name_to_number(self, month_name: str) -> int:
        """Convert month name to number."""
        months = {
            "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
            "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12
        }
        return months.get(month_name[:3].lower(), 12)

    def _month_to_quarter(self, month: int) -> str:
        """Convert month to Indian fiscal quarter."""
        if 1 <= month <= 3:
            return "Q4"  # Jan-Mar = Q4
        elif 4 <= month <= 6:
            return "Q1"  # Apr-Jun = Q1
        elif 7 <= month <= 9:
            return "Q2"  # Jul-Sep = Q2
        else:
            return "Q3"  # Oct-Dec = Q3

    def _month_to_fiscal_year(self, month: int, calendar_year: int) -> int:
        """
        Convert calendar month/year to Indian fiscal year.
        Indian FY: Apr-Mar (FY2026 = Apr 2025 - Mar 2026)
        """
        if 1 <= month <= 3:
            return calendar_year  # Jan-Mar: same year is FY
        else:
            return calendar_year + 1  # Apr-Dec: next year is FY

    def is_bank_pdf(self, text: str) -> bool:
        """Detect if PDF is for a bank/NBFC company."""
        if not text:
            return False

        lower_text = text.lower()
        indicators = [
            "net interest income",
            "interest earned",
            "interest expended",
            "gross npa",
            "net npa",
            "capital adequacy",
            "advances",
            "deposits"
        ]

        count = sum(1 for ind in indicators if ind in lower_text)
        return count >= 2

    def extract_tables_from_pdf(self, file_path: str) -> List[Dict]:
        """
        Extract tables from PDF using pdfplumber.
        Returns list of table dicts with page number and rows.
        """
        tables = []
        try:
            with pdfplumber.open(file_path) as pdf:
                for i, page in enumerate(pdf.pages[:15]):
                    page_tables = page.extract_tables()
                    if page_tables:
                        for j, table in enumerate(page_tables):
                            if table and len(table) > 1:
                                tables.append({
                                    "page": i + 1,
                                    "table_index": j,
                                    "rows": table
                                })

            logger.info(f"Extracted {len(tables)} tables from PDF")
            return tables

        except Exception as e:
            logger.error(f"Error extracting tables: {e}")
            return []

    def parse_metrics_from_tables(self, tables: List[Dict]) -> Dict[str, Optional[float]]:
        """
        Parse financial metrics from extracted tables.
        Looks for the consolidated results table and extracts values.
        """
        metrics = {}

        for table_info in tables:
            rows = table_info.get("rows", [])

            # Look for consolidated financial results table
            is_consolidated = False
            for row in rows[:5]:  # Check first few rows
                row_text = " ".join([str(cell) for cell in row if cell]).lower()
                if "consolidated" in row_text or "financial results" in row_text:
                    is_consolidated = True
                    break

            if not is_consolidated:
                continue

            # Parse each row
            for row in rows:
                if not row or len(row) < 2:
                    continue

                label = str(row[0] or "").lower().strip()

                # Find first numeric value (current quarter)
                for cell in row[1:]:
                    value = self._parse_cell_value(cell)
                    if value is not None:
                        # Match label to metric
                        if "revenue from operations" in label:
                            metrics["revenue"] = value
                        elif "other income" in label and "comprehensive" not in label:
                            metrics["other_income"] = value
                        elif "total income" in label and "comprehensive" not in label:
                            metrics["total_income"] = value
                        elif "total expenses" in label or "total expenditure" in label:
                            metrics["total_expenses"] = value
                        elif "profit before tax" in label:
                            metrics["pbt"] = value
                        elif "tax expense" in label:
                            metrics["tax"] = value
                        elif "profit for the period" in label or "net profit after tax" in label:
                            metrics["pat"] = value
                        elif "basic" in label and ("eps" in label or "rs" in label):
                            metrics["eps_basic"] = value
                        elif "diluted" in label and ("eps" in label or "rs" in label):
                            metrics["eps_diluted"] = value
                        break

        logger.info(f"Parsed metrics from tables: {list(metrics.keys())}")
        return metrics

    def _parse_cell_value(self, cell) -> Optional[float]:
        """Parse a table cell value to float."""
        if cell is None:
            return None

        text = str(cell).strip()
        if not text or text == '-':
            return None

        # Handle negative in parentheses
        is_negative = text.startswith('(') and text.endswith(')')
        if is_negative:
            text = text[1:-1]

        # Remove commas
        text = text.replace(',', '')

        try:
            value = float(text)
            return -value if is_negative else value
        except ValueError:
            return None

    def extract_metrics_from_text_direct(self, pdf_path: str) -> Tuple[Dict[str, Optional[float]], str]:
        """
        Extract financial metrics directly from PDF text.
        This is more reliable for Indian quarterly results than table extraction.
        Returns: (metrics_dict, unit)
        """
        metrics = {}
        unit = 'millions'  # Default for most Indian company results

        try:
            with pdfplumber.open(pdf_path) as pdf:
                all_text = ''
                for page in pdf.pages[:8]:
                    text = page.extract_text()
                    if text:
                        all_text += text + '\n'

                if not all_text:
                    logger.warning("No text extracted from PDF")
                    return {}, 'unknown'

                # Detect unit from text
                text_lower = all_text.lower()
                if 'in millions' in text_lower or 'in million' in text_lower:
                    unit = 'millions'
                elif 'in lakhs' in text_lower or 'in lakh' in text_lower:
                    unit = 'lakhs'
                elif 'in crores' in text_lower or 'in crore' in text_lower:
                    unit = 'crores'

                lines = all_text.split('\n')

                for line in lines:
                    line_lower = line.lower()

                    # Fix common OCR artifacts before extracting numbers
                    # Pattern like '8,t 15.60' should become '8,115.60'
                    fixed_line = re.sub(r'(\d),([tl1I]) (\d)', r'\g<1>,1\3', line)

                    # Find numbers with Indian formatting
                    # Must have comma or be 4+ digits with decimal to avoid row numbers
                    number_pattern = r'([0-9]{1,3}(?:,[0-9]{2,3})*\.[0-9]+|[0-9]{1,3}(?:,[0-9]{2,3})+|[0-9]{4,}\.[0-9]+)'
                    numbers_raw = re.findall(number_pattern, fixed_line)

                    numbers = []
                    for n in numbers_raw:
                        clean = n.replace(',', '')
                        try:
                            val = float(clean)
                            # Skip if looks like a year
                            if 2020 <= val <= 2030:
                                continue
                            numbers.append(val)
                        except:
                            pass

                    if not numbers:
                        continue

                    val = numbers[0]  # First number is current quarter

                    # Match to metrics using SEBI format patterns
                    if 'revenue from' in line_lower and 'revenue' not in metrics:
                        metrics['revenue'] = val
                    elif '(1+2)' in line and 'income' in line_lower and 'total_income' not in metrics:
                        metrics['total_income'] = val
                    elif '(4)' in line and 'expense' in line_lower and 'total_expenses' not in metrics:
                        metrics['total_expenses'] = val
                    elif ('(3-4)' in line or '(5-6)' in line) and 'before' in line_lower:
                        if 'pbt' not in metrics:
                            metrics['pbt'] = val
                    elif '(7-8)' in line and 'comprehensive' not in line_lower:
                        if 'pat' not in metrics:
                            metrics['pat'] = val
                    elif line.strip().startswith('(in') and 'eps_basic' not in metrics:
                        # EPS is typically a small number
                        for n in numbers:
                            if 0.01 <= n <= 200:
                                metrics['eps_basic'] = n
                                break

                logger.info(f"Direct text extraction found: {list(metrics.keys())}")
                return metrics, unit

        except Exception as e:
            logger.error(f"Error in direct text extraction: {e}")
            return {}, 'unknown'

    def convert_to_crores(self, value: float, unit: str) -> float:
        """Convert value to Crores based on the unit."""
        if unit == 'millions':
            return value / 10  # 10 million = 1 crore
        elif unit == 'lakhs':
            return value / 100  # 100 lakhs = 1 crore
        return value  # Already in crores or unknown
