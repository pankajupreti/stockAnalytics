"""
Debug standalone page parsing for NETWEB.
"""
import asyncio
import httpx
import re

async def main():
    ticker = "NETWEB"
    url = f"https://www.screener.in/company/{ticker}/"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    }

    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.get(url, headers=headers, follow_redirects=True)

        if response.status_code == 200:
            html = response.text

            # Find quarters section
            section_match = re.search(r'<section[^>]*id="quarters"[^>]*>(.*?)</section>', html, re.DOTALL)

            if section_match:
                section = section_match.group(1)

                # Find table
                table_match = re.search(r'<table[^>]*class="[^"]*data-table[^"]*"[^>]*>(.*?)</table>', section, re.DOTALL)

                if table_match:
                    table = table_match.group(1)

                    print("=== THEAD ===")
                    thead_match = re.search(r'<thead>(.*?)</thead>', table, re.DOTALL)
                    if thead_match:
                        thead = thead_match.group(1)
                        print(thead[:1000])

                        # Try to find th with dates
                        ths = re.findall(r'<th[^>]*>(.*?)</th>', thead, re.DOTALL)
                        print(f"\nTH elements: {len(ths)}")
                        for i, th in enumerate(ths[:10]):
                            clean = re.sub(r'<[^>]+>', '', th).strip()
                            print(f"  {i}: '{clean}'")

                    print("\n=== TBODY (first 1500 chars) ===")
                    tbody_match = re.search(r'<tbody>(.*?)</tbody>', table, re.DOTALL)
                    if tbody_match:
                        tbody = tbody_match.group(1)
                        print(tbody[:1500])

                        # Try to extract a row
                        rows = re.findall(r'<tr[^>]*>(.*?)</tr>', tbody, re.DOTALL)
                        print(f"\n{len(rows)} rows found")

                        for row in rows[:3]:
                            tds = re.findall(r'<td[^>]*>(.*?)</td>', row, re.DOTALL)
                            clean_tds = [re.sub(r'<[^>]+>', '', td).strip()[:20] for td in tds[:10]]
                            print(f"Row: {clean_tds}")

if __name__ == "__main__":
    asyncio.run(main())
