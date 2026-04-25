"""
Test OCR output analysis to understand text structure.
"""
import sys
sys.path.insert(0, '.')
from app.pdf_parser import PdfParserService

parser = PdfParserService()
result = parser.extract_from_file(r'C:\proj\OauthProj\result\anantraj.pdf')

if not result.get('success'):
    print('Extraction failed:', result.get('error'))
    sys.exit(1)

text = result.get('text', '')
print(f"Total chars: {len(text)}")
print(f"OCR used: {result.get('ocr_used')}")
print("="*60)

lines = text.split('\n')

# Look for key metrics
keywords = ['revenue from', 'profit for the period', 'profit before tax', 'total income', 'basic', 'eps']

print("Lines containing key metrics:")
print("="*60)

for i, line in enumerate(lines):
    line_lower = line.lower()
    for kw in keywords:
        if kw in line_lower:
            print(f"\n--- Line {i} (keyword: '{kw}') ---")
            # Show context: previous line, current, next 2 lines
            if i > 0:
                print(f"  {i-1}: {lines[i-1][:100]}")
            print(f"* {i}: {line[:100]}")
            if i+1 < len(lines):
                print(f"  {i+1}: {lines[i+1][:100]}")
            if i+2 < len(lines):
                print(f"  {i+2}: {lines[i+2][:100]}")
            break

# Also look for specific numbers we expect
print("\n" + "="*60)
print("Looking for expected values (641, 144, 4.14, 171):")
import re
for i, line in enumerate(lines):
    if re.search(r'641|144\.2|4\.14|171\.7', line):
        print(f"Line {i}: {line[:120]}")
