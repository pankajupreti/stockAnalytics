"""
Debug Screener.in scraper for NETWEB.
"""
import asyncio
import httpx
import re

async def main():
    ticker = "NETWEB"
    base_url = "https://www.screener.in"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    }

    # Try consolidated first
    url = f"{base_url}/company/{ticker}/consolidated/"
    print(f"Fetching: {url}")

    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.get(url, headers=headers, follow_redirects=True)
        print(f"Status: {response.status_code}")
        print(f"Final URL: {response.url}")

        if response.status_code == 404:
            # Try without consolidated
            url = f"{base_url}/company/{ticker}/"
            print(f"\nTrying: {url}")
            response = await client.get(url, headers=headers, follow_redirects=True)
            print(f"Status: {response.status_code}")
            print(f"Final URL: {response.url}")

        if response.status_code == 200:
            html = response.text

            # Look for the data-result-table
            print("\n--- Looking for quarterly table ---")

            # Check if table exists
            if 'data-result-table' in html:
                print("Found data-result-table!")
            else:
                print("No data-result-table found")

            # Find the quarters section
            quarters_match = re.search(r'id="quarters"', html)
            if quarters_match:
                print("Found quarters section!")

            # Try to find the table
            table_match = re.search(
                r'<section[^>]*id="quarters"[^>]*>(.*?)</section>',
                html, re.DOTALL
            )

            if table_match:
                section = table_match.group(1)
                print(f"\nQuarters section length: {len(section)} chars")

                # Look for headers
                th_pattern = r'<th[^>]*>([^<]*(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[^<]*\d{4}[^<]*)</th>'
                headers = re.findall(th_pattern, section, re.IGNORECASE)
                print(f"Headers found: {headers[:5]}")

                # Look for Sales row
                sales_match = re.search(r'Sales.*?<td[^>]*>([^<]+)</td>', section, re.DOTALL | re.IGNORECASE)
                if sales_match:
                    print(f"First Sales value: {sales_match.group(1)}")
            else:
                # Try different pattern
                print("\nTrying alternative table patterns...")

                # Look for any table with quarterly data
                all_tables = re.findall(r'<table[^>]*class="[^"]*data-table[^"]*"[^>]*>(.*?)</table>', html, re.DOTALL)
                print(f"Found {len(all_tables)} data-tables")

                for i, table in enumerate(all_tables[:3]):
                    if 'Sales' in table or 'Revenue' in table:
                        print(f"\nTable {i} contains Sales/Revenue!")
                        # Extract first few th elements
                        ths = re.findall(r'<th[^>]*>(.*?)</th>', table, re.DOTALL)[:8]
                        print(f"Headers: {[re.sub(r'<[^>]+>', '', th).strip()[:20] for th in ths]}")

if __name__ == "__main__":
    asyncio.run(main())
