"""
Find the actual Consolidated Financial Results table.
"""
import sys
import re
sys.path.insert(0, '.')
from app.pdf_parser import PdfParserService

parser = PdfParserService()
result = parser.extract_from_file(r'C:\proj\OauthProj\result\anantraj.pdf')

text = result.get('text', '')

# Look for "Statement of Unaudited Consolidated"
patterns = [
    "statement of unaudited consolidated",
    "consolidated financial results",
    "unaudited consolidated",
]

text_lower = text.lower()

for pattern in patterns:
    idx = text_lower.find(pattern)
    if idx > 0:
        print(f"Found '{pattern}' at index {idx}")
        # Show the next 3000 chars
        section = text[idx:idx + 3000]
        print(f"\n{'='*60}")
        print(f"Text from '{pattern}':")
        print(f"{'='*60}")
        print(section)

        # Extract profit lines
        print(f"\n{'='*60}")
        print("Lines with 'profit' in this section:")
        print(f"{'='*60}")
        lines = section.split('\n')
        for i, line in enumerate(lines):
            if 'profit' in line.lower():
                print(f"{i}: {line}")
        break
