"""
Test to check both Consolidated and Standalone results from Screener.in
"""
import asyncio
import httpx
import re

async def test_both():
    ticker = "TCS"
    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
    }

    async with httpx.AsyncClient(timeout=30, follow_redirects=True) as client:
        # Fetch the main page
        url = f'https://www.screener.in/company/{ticker}/consolidated/'
        print(f'Fetching: {url}')
        response = await client.get(url, headers=headers)
        print(f'Status: {response.status_code}')

        html = response.text

        # Look for consolidated/standalone tabs or sections
        print("\n--- Searching for Consolidated/Standalone sections ---")

        # Check for tabs
        if 'Standalone' in html:
            print("Found 'Standalone' text in HTML")
        if 'Consolidated' in html:
            print("Found 'Consolidated' text in HTML")

        # Look for data-result-table sections
        tables = re.findall(r'<section[^>]*id="quarters"[^>]*>(.*?)</section>', html, re.DOTALL)
        print(f"Found {len(tables)} quarters sections")

        # Look for multiple data-result-table divs
        result_tables = re.findall(r'<div[^>]*data-result-table[^>]*>', html)
        print(f"Found {len(result_tables)} data-result-table divs")

        # Check for tab structure
        tab_matches = re.findall(r'<(button|a)[^>]*(consolidated|standalone)[^>]*>', html, re.IGNORECASE)
        print(f"Found {len(tab_matches)} tab buttons/links")

        # Look for specific class patterns
        if 'quarters-standalone' in html.lower():
            print("Found 'quarters-standalone' class")
        if 'quarters-consolidated' in html.lower():
            print("Found 'quarters-consolidated' class")

        # Save HTML for manual inspection
        with open('screener_tcs.html', 'w', encoding='utf-8') as f:
            f.write(html)
        print("\nSaved HTML to screener_tcs.html for inspection")

        # Try the standalone URL directly
        print("\n--- Testing Standalone URL ---")
        url2 = f'https://www.screener.in/company/{ticker}/'
        response2 = await client.get(url2, headers=headers)
        print(f'Standalone URL status: {response2.status_code}')
        print(f'Final URL: {response2.url}')
        print(f'Same as consolidated: {response.text == response2.text}')

if __name__ == "__main__":
    asyncio.run(test_both())
