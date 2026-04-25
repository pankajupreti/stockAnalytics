"""
Look at the consolidated section specifically.
"""
import sys
import re
sys.path.insert(0, '.')
from app.pdf_parser import PdfParserService

parser = PdfParserService()
result = parser.extract_from_file(r'C:\proj\OauthProj\result\anantraj.pdf')

text = result.get('text', '')

# Find the consolidated section
consolidated_idx = text.lower().find("consolidated")
print(f"Found 'consolidated' at index {consolidated_idx}")

# Get text from consolidated section
if consolidated_idx > 0:
    consolidated_text = text[consolidated_idx:consolidated_idx + 5000]
    print("\n" + "="*60)
    print("First 5000 chars of consolidated section:")
    print("="*60)
    print(consolidated_text)

    # Find profit lines in this section
    print("\n" + "="*60)
    print("Lines with 'profit' in consolidated section:")
    print("="*60)
    lines = consolidated_text.split('\n')
    for i, line in enumerate(lines):
        if 'profit' in line.lower():
            print(f"{i}: {line}")
