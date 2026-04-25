"""
PDF Parser Service - Handles PDF text extraction with OCR fallback.
Supports both text-based and scanned PDFs.
Uses multiple extraction methods: PyMuPDF (fitz), pdfplumber, and OCR.
"""

import os
import tempfile
import logging
from typing import Dict, Any, Optional
from pathlib import Path

import httpx
import pdfplumber

# PyMuPDF for better text extraction
try:
    import fitz  # PyMuPDF
    HAS_PYMUPDF = True
except ImportError:
    HAS_PYMUPDF = False

# OCR support (optional)
try:
    from pdf2image import convert_from_path
    import pytesseract
    from PIL import Image
    HAS_OCR = True
except ImportError:
    HAS_OCR = False

logger = logging.getLogger(__name__)

# Tesseract path for Windows
if os.name == 'nt':
    tesseract_paths = [
        r'C:\Users\pankaj.upreti\AppData\Local\Programs\Tesseract-OCR\tesseract.exe',
        r'C:\Program Files\Tesseract-OCR\tesseract.exe',
    ]
    for tesseract_path in tesseract_paths:
        if os.path.exists(tesseract_path):
            pytesseract.pytesseract.tesseract_cmd = tesseract_path
            break

    # Poppler path for pdf2image
    poppler_paths = [
        r'C:\proj\OauthProj\results-service-python\Release-25.12.0-0\poppler-25.12.0\Library\bin',
        r'C:\Program Files\poppler\bin',
    ]
    for poppler_path in poppler_paths:
        if os.path.exists(poppler_path):
            os.environ['PATH'] = poppler_path + os.pathsep + os.environ.get('PATH', '')
            break


class PdfParserService:
    """Service for extracting text from PDFs with OCR support."""

    def __init__(self):
        self.min_text_length = 500  # Minimum chars per page to consider it text-based
        self.download_timeout = 30
        self.max_pages = 15
        self.ocr_dpi = 300

    def extract_from_file(self, file_path: str) -> Dict[str, Any]:
        """
        Extract text from a local PDF file.
        Uses hybrid approach - extracts text from text pages, OCR from scanned pages.
        """
        try:
            logger.info(f"Extracting text from: {file_path}")

            if not os.path.exists(file_path):
                return {"success": False, "error": f"File not found: {file_path}"}

            # Use hybrid extraction - text for text pages, OCR for scanned pages
            return self._extract_hybrid(file_path)

        except Exception as e:
            logger.error(f"Error extracting from file: {e}", exc_info=True)
            return {"success": False, "error": str(e)}

    def _extract_hybrid(self, file_path: str) -> Dict[str, Any]:
        """
        Hybrid extraction - uses text extraction for pages with text,
        OCR for pages that appear to be scanned images.
        """
        try:
            all_text = []
            ocr_used = False
            total_pages = 0
            ocr_available = HAS_OCR and self._is_tesseract_available()

            if HAS_PYMUPDF:
                doc = fitz.open(file_path)
                total_pages = len(doc)
                pages_to_process = min(total_pages, self.max_pages)

                logger.info(f"Processing {pages_to_process} pages (hybrid mode, OCR available: {ocr_available})")

                for i in range(pages_to_process):
                    page = doc[i]
                    text = page.get_text("text").strip()

                    if len(text) >= self.min_text_length:
                        # Page has enough text
                        all_text.append(text)
                        logger.debug(f"Page {i+1}: {len(text)} chars (text)")
                    elif ocr_available:
                        # Page likely scanned - use OCR
                        logger.info(f"Page {i+1}: Only {len(text)} chars, using OCR...")
                        ocr_text = self._ocr_single_page(file_path, i)
                        if ocr_text and len(ocr_text) > len(text):
                            all_text.append(ocr_text)
                            ocr_used = True
                            logger.debug(f"Page {i+1}: {len(ocr_text)} chars (OCR)")
                        else:
                            all_text.append(text)
                    else:
                        # No OCR, keep whatever we have
                        all_text.append(text)
                        logger.debug(f"Page {i+1}: {len(text)} chars (no OCR)")

                doc.close()

            else:
                # Fallback to pdfplumber
                return self._extract_with_pdfplumber(file_path)

            full_text = "\n\n".join(all_text)
            logger.info(f"Hybrid extraction complete: {len(full_text)} chars, OCR used: {ocr_used}")

            return {
                "success": True,
                "text": full_text,
                "pages": total_pages,
                "ocr_used": ocr_used,
                "method": "hybrid"
            }

        except Exception as e:
            logger.error(f"Hybrid extraction failed: {e}", exc_info=True)
            return {"success": False, "error": str(e)}

    def _ocr_single_page(self, file_path: str, page_index: int) -> Optional[str]:
        """OCR a single page of the PDF."""
        try:
            from pdf2image import convert_from_path

            # Get poppler path
            poppler_path = None
            for p in [
                r'C:\proj\OauthProj\results-service-python\Release-25.12.0-0\poppler-25.12.0\Library\bin',
                r'C:\Program Files\poppler\bin',
            ]:
                if os.path.exists(p):
                    poppler_path = p
                    break

            # Convert single page to image
            images = convert_from_path(
                file_path,
                first_page=page_index + 1,
                last_page=page_index + 1,
                dpi=self.ocr_dpi,
                poppler_path=poppler_path
            )

            if not images:
                return None

            # OCR the image with PSM 6 (assume single block of text) for better table recognition
            # This helps with structured financial tables
            text = pytesseract.image_to_string(images[0], lang='eng', config='--psm 6')
            return text.strip() if text else None

        except Exception as e:
            logger.warning(f"OCR failed for page {page_index + 1}: {e}")
            return None

    def _extract_with_pymupdf(self, file_path: str) -> Dict[str, Any]:
        """Extract text using PyMuPDF (fitz)."""
        try:
            doc = fitz.open(file_path)
            total_pages = len(doc)
            pages_to_process = min(total_pages, self.max_pages)

            all_text = []
            for i in range(pages_to_process):
                page = doc[i]
                text = page.get_text("text")
                all_text.append(text)
                logger.debug(f"Page {i+1}: Extracted {len(text)} chars (PyMuPDF)")

            doc.close()
            full_text = "\n\n".join(all_text)

            return {
                "success": True,
                "text": full_text,
                "pages": total_pages,
                "ocr_used": False,
                "method": "pymupdf"
            }

        except Exception as e:
            logger.warning(f"PyMuPDF extraction failed: {e}")
            return {"success": False, "error": str(e)}

    def _extract_with_pdfplumber(self, file_path: str) -> Dict[str, Any]:
        """Extract text using pdfplumber."""
        try:
            all_text = []
            total_pages = 0

            with pdfplumber.open(file_path) as pdf:
                total_pages = len(pdf.pages)
                pages_to_process = min(total_pages, self.max_pages)

                for i, page in enumerate(pdf.pages[:pages_to_process]):
                    text = page.extract_text() or ""
                    all_text.append(text)
                    logger.debug(f"Page {i+1}: Extracted {len(text)} chars (pdfplumber)")

            full_text = "\n\n".join(all_text)

            return {
                "success": True,
                "text": full_text,
                "pages": total_pages,
                "ocr_used": False,
                "method": "pdfplumber"
            }

        except Exception as e:
            logger.warning(f"pdfplumber extraction failed: {e}")
            return {"success": False, "error": str(e)}

    def _extract_with_ocr(self, file_path: str) -> Dict[str, Any]:
        """Extract text using OCR (for scanned PDFs)."""
        try:
            all_text = []
            total_pages = 0

            with pdfplumber.open(file_path) as pdf:
                total_pages = len(pdf.pages)
                pages_to_process = min(total_pages, self.max_pages)

                for i in range(pages_to_process):
                    # First try regular extraction
                    text = pdf.pages[i].extract_text() or ""

                    if len(text.strip()) < self.min_text_length:
                        # Use OCR
                        ocr_text = self._ocr_page(file_path, i)
                        if ocr_text:
                            text = ocr_text

                    all_text.append(text)

            full_text = "\n\n".join(all_text)

            return {
                "success": True,
                "text": full_text,
                "pages": total_pages,
                "ocr_used": True,
                "method": "ocr"
            }

        except Exception as e:
            logger.warning(f"OCR extraction failed: {e}")
            return {"success": False, "error": str(e)}

    async def extract_from_url(self, pdf_url: str) -> Dict[str, Any]:
        """
        Download PDF from URL and extract text.
        """
        try:
            logger.info(f"Downloading PDF from: {pdf_url}")

            # Download to temp file
            temp_path = await self.download_pdf(pdf_url)
            if not temp_path:
                return {"success": False, "error": "Failed to download PDF"}

            try:
                # Extract text
                result = self.extract_from_file(temp_path)
                return result
            finally:
                # Cleanup temp file
                try:
                    os.unlink(temp_path)
                except:
                    pass

        except Exception as e:
            logger.error(f"Error extracting from URL: {e}", exc_info=True)
            return {"success": False, "error": str(e)}

    async def download_pdf(self, pdf_url: str) -> Optional[str]:
        """Download PDF to a temporary file."""
        try:
            async with httpx.AsyncClient(timeout=self.download_timeout) as client:
                response = await client.get(
                    pdf_url,
                    headers={
                        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Accept": "application/pdf"
                    },
                    follow_redirects=True
                )

                if response.status_code != 200:
                    logger.error(f"Failed to download PDF: HTTP {response.status_code}")
                    return None

                # Save to temp file
                with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as f:
                    f.write(response.content)
                    return f.name

        except Exception as e:
            logger.error(f"Error downloading PDF: {e}")
            return None

    def _ocr_page(self, pdf_path: str, page_index: int) -> Optional[str]:
        """
        OCR a single page of the PDF.
        Converts page to image and runs Tesseract.
        """
        try:
            # Check if Tesseract is available
            if not self._is_tesseract_available():
                logger.warning("Tesseract not available, skipping OCR")
                return None

            # Convert specific page to image
            images = convert_from_path(
                pdf_path,
                first_page=page_index + 1,
                last_page=page_index + 1,
                dpi=self.ocr_dpi
            )

            if not images:
                return None

            # OCR the image
            text = pytesseract.image_to_string(images[0], lang='eng')
            return text

        except Exception as e:
            logger.warning(f"OCR failed for page {page_index + 1}: {e}")
            return None

    def _is_tesseract_available(self) -> bool:
        """Check if Tesseract is installed and available."""
        try:
            pytesseract.get_tesseract_version()
            return True
        except:
            return False

    def extract_tables(self, file_path: str) -> list:
        """
        Extract tables from PDF using pdfplumber.
        Returns list of tables, each table is a list of rows.
        """
        tables = []
        try:
            with pdfplumber.open(file_path) as pdf:
                for i, page in enumerate(pdf.pages[:self.max_pages]):
                    page_tables = page.extract_tables()
                    if page_tables:
                        for table in page_tables:
                            if table and len(table) > 1:  # At least header + 1 row
                                tables.append({
                                    "page": i + 1,
                                    "rows": table
                                })
                        logger.debug(f"Page {i+1}: Found {len(page_tables)} tables")

            logger.info(f"Total tables extracted: {len(tables)}")
            return tables

        except Exception as e:
            logger.error(f"Error extracting tables: {e}")
            return []
