"""
Extract and examine each page separately to find the financial results table.
"""
import sys
import os
sys.path.insert(0, '.')

# Import PyMuPDF
import fitz

pdf_path = r'C:\proj\OauthProj\result\anantraj.pdf'

doc = fitz.open(pdf_path)
print(f"PDF has {len(doc)} pages")
print("=" * 80)

# Look at each page
for i in range(len(doc)):
    page = doc[i]
    text = page.get_text("text")

    # Check if this page has key financial terms
    has_revenue = "revenue from operations" in text.lower()
    has_consolidated = "consolidated" in text.lower() and "financial" in text.lower()
    has_pat = "profit for the period" in text.lower() or "profit after tax" in text.lower()

    print(f"\nPage {i+1}: {len(text)} chars")
    print(f"  Has 'Revenue from operations': {has_revenue}")
    print(f"  Has 'Consolidated Financial': {has_consolidated}")
    print(f"  Has 'Profit for the period': {has_pat}")

    if has_revenue or (has_consolidated and has_pat):
        print(f"\n  *** This looks like the financial results page! ***")
        print(f"\n  First 2000 chars of page {i+1}:")
        print("-" * 60)
        print(text[:2000])
        print("-" * 60)

doc.close()
