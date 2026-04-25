"""
Test full extraction pipeline with anantraj.pdf
"""
import sys
sys.path.insert(0, '.')
from app.pdf_parser import PdfParserService
from app.table_extractor import QuarterlyResultsExtractor

# Expected values from the PDF (Q3 FY26 - Dec 2025 quarter)
EXPECTED = {
    "revenue": 641.59,
    "pat": 144.23,
    "eps_basic": 4.14,
    "pbt": 171.78
}

print("=" * 60)
print("Testing PDF extraction with anantraj.pdf")
print("=" * 60)

# Step 1: Extract text from PDF
parser = PdfParserService()
result = parser.extract_from_file(r'C:\proj\OauthProj\result\anantraj.pdf')

if not result.get('success'):
    print(f"PDF extraction failed: {result.get('error')}")
    sys.exit(1)

text = result.get('text', '')
print(f"\nStep 1: PDF text extraction")
print(f"  Total chars: {len(text)}")
print(f"  OCR used: {result.get('ocr_used')}")
print(f"  Method: {result.get('method')}")

# Step 2: Extract metrics
extractor = QuarterlyResultsExtractor()
metrics = extractor.extract_metrics(text, is_ocr=result.get('ocr_used', False))

print(f"\nStep 2: Metric extraction")
print(f"  Found {len(metrics)} metrics:")
for key, value in metrics.items():
    print(f"    {key}: {value}")

# Step 3: Verify against expected
print(f"\n" + "=" * 60)
print("VERIFICATION:")
all_pass = True
for metric, expected in EXPECTED.items():
    actual = metrics.get(metric)
    if actual is None:
        print(f"  {metric}: NOT FOUND (expected: {expected}) - FAIL")
        all_pass = False
    else:
        # Allow 5% tolerance for OCR
        diff_pct = abs(actual - expected) / expected * 100 if expected != 0 else 0
        status = "PASS" if diff_pct < 5 else "FAIL"
        print(f"  {metric}: {actual} (expected: {expected}, diff: {diff_pct:.1f}%) - {status}")
        if status == "FAIL":
            all_pass = False

print("=" * 60)
if all_pass:
    print("ALL TESTS PASSED!")
else:
    print("SOME TESTS FAILED - extraction needs improvement")
