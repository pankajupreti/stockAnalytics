"""
Test script for PDF parser.
Tests parsing of anantraj.pdf to verify metrics extraction.
"""

import sys
sys.path.insert(0, '.')

from app.pdf_parser import PdfParserService
from app.table_extractor import QuarterlyResultsExtractor

# Expected values from anantraj.pdf (Page 9 - Consolidated)
EXPECTED = {
    "revenue": 641.59,
    "pat": 144.23,
    "pbt": 171.78,
    "eps_basic": 4.14
}

def main():
    pdf_path = "C:/proj/OauthProj/result/anantraj.pdf"

    print("=" * 60)
    print("Testing Python PDF Parser")
    print("=" * 60)
    print(f"File: {pdf_path}")
    print()

    # Initialize services
    parser = PdfParserService()
    extractor = QuarterlyResultsExtractor()

    # Extract text
    print("Step 1: Extracting text from PDF...")
    result = parser.extract_from_file(pdf_path)

    if not result["success"]:
        print(f"ERROR: {result.get('error')}")
        return

    text = result["text"]
    print(f"  Extracted {len(text)} characters")
    print(f"  Pages: {result.get('pages', 'unknown')}")
    print(f"  OCR used: {result.get('ocr_used', False)}")
    print()

    # Show preview
    print("Text preview (first 500 chars):")
    print("-" * 40)
    print(text[:500].replace('\n', ' '))
    print("-" * 40)
    print()

    # Extract metrics
    print("Step 2: Extracting financial metrics...")
    metrics = extractor.extract_metrics(text)

    print(f"  Found {len(metrics)} metrics:")
    for k, v in metrics.items():
        print(f"    {k}: {v}")
    print()

    # Extract quarter info
    print("Step 3: Extracting quarter info...")
    quarter_info = extractor.extract_quarter_info(text)
    print(f"  Quarter: {quarter_info.get('quarter')}")
    print(f"  Fiscal Year: {quarter_info.get('fiscal_year')}")
    print()

    # Check if bank
    print("Step 4: Checking company type...")
    is_bank = extractor.is_bank_pdf(text)
    print(f"  Is Bank/NBFC: {is_bank}")
    print()

    # Verify against expected values
    print("=" * 60)
    print("VERIFICATION")
    print("=" * 60)

    all_pass = True
    for metric, expected in EXPECTED.items():
        actual = metrics.get(metric)
        if actual is not None:
            diff = abs(actual - expected)
            status = "PASS" if diff < 1 else "CLOSE" if diff < 10 else "FAIL"
            if status == "FAIL":
                all_pass = False
            print(f"  {metric}: {actual} (expected: {expected}) - {status}")
        else:
            all_pass = False
            print(f"  {metric}: NOT FOUND (expected: {expected}) - FAIL")

    print()
    if all_pass:
        print("All metrics verified successfully!")
    else:
        print("Some metrics did not match. OCR may be needed for scanned pages.")

        # Try table extraction
        print()
        print("Step 5: Trying table extraction...")
        tables = extractor.extract_tables_from_pdf(pdf_path)
        print(f"  Found {len(tables)} tables")

        if tables:
            table_metrics = extractor.parse_metrics_from_tables(tables)
            print(f"  Metrics from tables: {table_metrics}")

if __name__ == "__main__":
    main()
