"""
Test table finding pattern for NETWEB.
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
        html = response.text

        # Pattern 1 - current pattern
        pattern1 = r'<div[^>]*data-result-table[^>]*>\s*<table[^>]*class="data-table[^"]*"[^>]*>(.*?)</table>'
        match1 = re.search(pattern1, html, re.DOTALL)
        print(f"Pattern 1 (current): {'FOUND' if match1 else 'NOT FOUND'}")
        if match1:
            content = match1.group(1)[:200]
            print(f"  Content preview: {content}")

        # Pattern 2 - more flexible
        pattern2 = r'<div[^>]*data-result-table[^>]*>.*?<table[^>]*>(.*?)</table>'
        match2 = re.search(pattern2, html, re.DOTALL)
        print(f"\nPattern 2 (flexible): {'FOUND' if match2 else 'NOT FOUND'}")

        # Pattern 3 - find within quarters section
        quarters_section = re.search(r'<section[^>]*id="quarters"[^>]*>(.*?)</section>', html, re.DOTALL)
        if quarters_section:
            section = quarters_section.group(1)
            print(f"\nQuarters section: FOUND ({len(section)} chars)")

            # Find data-result-table in section
            data_table = re.search(r'data-result-table', section)
            print(f"  data-result-table in section: {'YES' if data_table else 'NO'}")

            # Find table in section
            table_in_section = re.search(r'<table[^>]*class="[^"]*data-table[^"]*"[^>]*>(.*?)</table>', section, re.DOTALL)
            print(f"  Table with data-table class: {'FOUND' if table_in_section else 'NOT FOUND'}")

            if table_in_section:
                table_content = table_in_section.group(1)
                # Check for thead
                thead = re.search(r'<thead>(.*?)</thead>', table_content, re.DOTALL)
                print(f"  Thead: {'FOUND' if thead else 'NOT FOUND'}")
                if thead:
                    # Extract headers
                    th_pattern = r'<th[^>]*>\s*((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+\d{4})'
                    headers = re.findall(th_pattern, thead.group(1), re.IGNORECASE)
                    print(f"  Headers: {headers[:5]}")

if __name__ == "__main__":
    asyncio.run(main())
