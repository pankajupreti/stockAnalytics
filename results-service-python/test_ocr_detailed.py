"""
Detailed OCR output analysis - focus on the financial results page.
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
print(f"Total chars: {len(text)}")
print(f"OCR used: {result.get('ocr_used')}")
print("="*80)

lines = text.split('\n')

# Print lines around the key metric sections (lines 230-260 and 500-560)
print("\n--- SECTION 1: Lines 230-260 (Profit before tax area) ---")
for i in range(230, min(261, len(lines))):
    print(f"{i:3d}: {lines[i]}")

print("\n--- SECTION 2: Lines 500-560 (Profit for the period area) ---")
for i in range(500, min(561, len(lines))):
    print(f"{i:3d}: {lines[i]}")

# Also find lines with revenue
print("\n--- REVENUE LINES ---")
for i, line in enumerate(lines):
    if 'revenue' in line.lower():
        print(f"{i:3d}: {line[:120]}")

# Find lines with numbers like 641, 171, 144
print("\n--- LINES WITH KEY NUMBERS ---")
for i, line in enumerate(lines):
    if re.search(r'\b6[34][0-9]', line) or re.search(r'\b17[0-9]', line) or re.search(r'\b14[34]', line):
        print(f"{i:3d}: {line[:120]}")
