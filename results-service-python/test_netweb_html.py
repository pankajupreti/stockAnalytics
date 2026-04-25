"""
Check the actual HTML structure for NETWEB quarters section.
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

            # Find quarters section
            section_match = re.search(r'<section[^>]*id="quarters"[^>]*>(.*?)</section>', html, re.DOTALL)

            if section_match:
                section = section_match.group(1)
                print("=== QUARTERS SECTION (first 2000 chars) ===")
                print(section[:2000])
                print("\n\n=== Looking for thead ===")

                thead_match = re.search(r'<thead>(.*?)</thead>', section, re.DOTALL)
                if thead_match:
                    print("THEAD found:")
                    print(thead_match.group(1)[:500])
                else:
                    print("No thead found!")

if __name__ == "__main__":
    asyncio.run(main())
