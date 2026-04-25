"""
Debug pattern matching for OCR text.
"""
import sys
import re
sys.path.insert(0, '.')
from app.pdf_parser import PdfParserService

parser = PdfParserService()
result = parser.extract_from_file(r'C:\proj\OauthProj\result\anantraj.pdf')

if not result.get('success'):
    print(f"Failed: {result.get('error')}")
    sys.exit(1)

text = result.get('text', '')
print(f"Extracted {len(text)} chars")

# Clean like the OCRTextProcessor does
clean_text = re.sub(r'[|_\[\]{}]', ' ', text)
clean_text = re.sub(r'\s+', ' ', clean_text)
clean_lower = clean_text.lower()

# Test patterns
patterns_to_test = {
    "revenue": r"revenue\s+from\s+operations?\s+([0-9,.]+)",
    "pbt": r"profit\s+before\s+tax[^\d]*([0-9,.]+)",
    "pat1": r"profit\s+for\s+the\s+period[/\s]*(year)?[^\d]*([0-9,.]+)",
    "pat2": r"profit\s+after\s+tax[^\d]*([0-9,.]+)",
    "eps": r"basic\s*\(?\s*rs\.?\s*\)?[^\d]*([0-9,.]+)",
}

print("\n" + "="*60)
print("Pattern matching results:")
print("="*60)

for name, pattern in patterns_to_test.items():
    matches = list(re.finditer(pattern, clean_lower))
    print(f"\n{name}: {len(matches)} matches")
    for i, match in enumerate(matches[:3]):  # Show first 3
        # Get context around match
        start = max(0, match.start() - 30)
        end = min(len(clean_lower), match.end() + 50)
        context = clean_lower[start:end]
        print(f"  {i+1}. Groups: {match.groups()}")
        print(f"     Context: ...{context}...")

# Also directly look for the expected values
print("\n" + "="*60)
print("Looking for specific numbers:")
print("="*60)

for target in ["171.78", "171", "144.23", "144", "4.14"]:
    if target in text:
        idx = text.find(target)
        context = text[max(0, idx-50):min(len(text), idx+50)]
        print(f"\n'{target}' found at index {idx}:")
        print(f"  ...{context}...")
    else:
        print(f"\n'{target}' NOT found in text")
