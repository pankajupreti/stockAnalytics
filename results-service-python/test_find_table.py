"""
Find the consolidated financial results table in OCR text.
"""
import sys
import re
sys.path.insert(0, '.')
from app.pdf_parser import PdfParserService

parser = PdfParserService()
result = parser.extract_from_file(r'C:\proj\OauthProj\result\anantraj.pdf')

if not result.get('success'):
    print('Extraction failed:', result.get('error'))
    sys.exit(1)

text = result.get('text', '')
lines = text.split('\n')

# Look for "Consolidated" or "Statement" which typically starts the results table
print("Looking for consolidated financial results section...")
print("=" * 80)

for i, line in enumerate(lines):
    line_lower = line.lower()
    if 'consolidated' in line_lower or 'statement of' in line_lower or 'financial results' in line_lower:
        # Print 30 lines after this marker
        print(f"\n--- Found at line {i}: {line[:80]} ---")
        for j in range(i, min(i + 40, len(lines))):
            print(f"{j:3d}: {lines[j][:100]}")
        print()
        break

# Also look at standalone results (since this is consolidated)
print("\n\nLooking for 'Revenue from operations'...")
print("=" * 80)
for i, line in enumerate(lines):
    if 'revenue from' in line.lower():
        print(f"\nFound at line {i}:")
        for j in range(max(0, i-2), min(i+5, len(lines))):
            print(f"{j:3d}: {lines[j][:100]}")
        break
