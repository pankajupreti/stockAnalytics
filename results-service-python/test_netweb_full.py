"""
Check full NETWEB page for data patterns.
"""
import asyncio
import httpx
import re

async def main():
    ticker = "NETWEB"
    url = f"https://www.screener.in/company/{ticker}/consolidated/"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    }

    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.get(url, headers=headers, follow_redirects=True)

        if response.status_code == 200:
            html = response.text

            # Look for any date patterns in the page
            print("=== Looking for date patterns ===")
            date_patterns = re.findall(r'((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s*\d{4})', html)
            unique_dates = list(set(date_patterns))[:10]
            print(f"Dates found: {unique_dates}")

            # Check if there's a data attribute with JSON
            print("\n=== Looking for data attributes ===")
            data_attrs = re.findall(r'data-[a-z-]+="([^"]*)"', html)
            for attr in data_attrs[:10]:
                if len(attr) > 20:
                    print(f"  {attr[:100]}...")

            # Check for JavaScript data
            print("\n=== Looking for JavaScript data ===")
            js_data = re.findall(r'var\s+(\w+)\s*=\s*(\{[^;]+\}|\[[^\]]+\])', html)
            for name, data in js_data[:5]:
                print(f"  {name}: {data[:100]}...")

            # Check for any numbers that look like financial data
            print("\n=== Looking for Sales/Revenue patterns ===")
            sales_patterns = re.findall(r'Sales.*?(\d+\.?\d*)', html[:50000], re.DOTALL | re.IGNORECASE)
            print(f"Sales numbers: {sales_patterns[:10]}")

            # Check the standalone page instead
            print("\n=== Trying standalone page ===")
            url2 = f"https://www.screener.in/company/{ticker}/"
            response2 = await client.get(url2, headers=headers, follow_redirects=True)
            html2 = response2.text

            # Check if standalone has more data
            section2 = re.search(r'<section[^>]*id="quarters"[^>]*>(.*?)</section>', html2, re.DOTALL)
            if section2:
                section_text = section2.group(1)
                dates2 = re.findall(r'((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s*\d{4})', section_text)
                print(f"Standalone dates: {dates2[:10]}")

                # Check for td elements with numbers
                tds = re.findall(r'<td[^>]*>([0-9,.]+)</td>', section_text)
                print(f"TD values: {tds[:10]}")

if __name__ == "__main__":
    asyncio.run(main())
