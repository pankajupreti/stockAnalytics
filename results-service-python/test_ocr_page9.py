"""
Extract OCR text from page 9 (consolidated results page) specifically.
"""
import sys
import os
sys.path.insert(0, '.')

# For OCR
from pdf2image import convert_from_path
import pytesseract

# Set up paths
tesseract_path = r'C:\Users\pankaj.upreti\AppData\Local\Programs\Tesseract-OCR\tesseract.exe'
poppler_path = r'C:\proj\OauthProj\results-service-python\Release-25.12.0-0\poppler-25.12.0\Library\bin'

if os.path.exists(tesseract_path):
    pytesseract.pytesseract.tesseract_cmd = tesseract_path
    print(f"Tesseract: {tesseract_path}")

pdf_path = r'C:\proj\OauthProj\result\anantraj.pdf'

# Convert page 9 to image (page 9 = 0-indexed page 8)
print(f"Converting page 9 to image...")
images = convert_from_path(
    pdf_path,
    first_page=9,
    last_page=9,
    dpi=300,
    poppler_path=poppler_path
)

if images:
    print(f"Running OCR on page 9...")
    # Use different OCR configurations

    # Default
    text_default = pytesseract.image_to_string(images[0], lang='eng')
    print(f"\n{'='*60}")
    print("OCR OUTPUT (Default config):")
    print(f"{'='*60}")
    print(text_default[:3000])

    # Also try with PSM 6 (assume a single uniform block of text)
    print(f"\n{'='*60}")
    print("OCR OUTPUT (PSM 6 - block mode):")
    print(f"{'='*60}")
    text_psm6 = pytesseract.image_to_string(images[0], lang='eng', config='--psm 6')
    print(text_psm6[:3000])
else:
    print("Failed to convert page to image")
