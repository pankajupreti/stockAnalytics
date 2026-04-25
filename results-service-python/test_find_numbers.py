"""
Find all decimal numbers in OCR text and list them.
"""
import sys
import re
sys.path.insert(0, '.')
from app.pdf_parser import PdfParserService

parser = PdfParserService()
result = parser.extract_from_file(r'C:\proj\OauthProj\result\anantraj.pdf')

text = result.get('text', '')

# Find all decimal numbers
numbers = re.findall(r'([0-9]+\.[0-9]+)', text)
unique_numbers = sorted(set(float(n) for n in numbers), reverse=True)

print(f"Found {len(unique_numbers)} unique decimal numbers")
print("\nNumbers between 100-200 (looking for ~171.78 PBT):")
for n in unique_numbers:
    if 100 <= n <= 200:
        print(f"  {n}")

print("\nNumbers between 140-150 (looking for ~144.23 PAT):")
for n in unique_numbers:
    if 140 <= n <= 150:
        print(f"  {n}")

print("\nNumbers between 3-6 (looking for ~4.14 EPS):")
for n in unique_numbers:
    if 3 <= n <= 6:
        print(f"  {n}")

print("\nNumbers between 600-700 (looking for ~641.59 Revenue):")
for n in unique_numbers:
    if 600 <= n <= 700:
        print(f"  {n}")

# Also look for the specific context around profit before tax
print("\n" + "="*60)
print("Context around 'profit before tax' lines:")
print("="*60)
lines = text.split('\n')
for i, line in enumerate(lines):
    if 'profit before tax' in line.lower():
        print(f"\nLine {i}: {line[:120]}")
        if i+1 < len(lines):
            print(f"Line {i+1}: {lines[i+1][:120]}")
