"""
Test Screener.in scraper for NETWEB.
"""
import asyncio
import sys
sys.path.insert(0, '.')

from app.screener_scraper import ScreenerScraperService

async def main():
    scraper = ScreenerScraperService()

    # Try different ticker variations
    tickers = ["NETWEB", "NETWEB TECH", "NETWEBTECHNOLOGIES"]

    for ticker in tickers:
        print(f"\nTrying ticker: {ticker}")
        print("=" * 60)
        result = await scraper.fetch_quarterly_results(ticker)

        if result.get("success"):
            print(f"Success! Found {len(result.get('results', []))} quarters")
            for r in result.get("results", [])[:2]:
                print(f"  {r.get('quarterLabel')}: Revenue={r.get('revenue')}, PAT={r.get('pat')}")
        else:
            print(f"Failed: {result.get('error')}")

if __name__ == "__main__":
    asyncio.run(main())
