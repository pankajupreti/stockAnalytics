"""
Test Screener.in scraper for Anant Raj.
"""
import asyncio
import sys
sys.path.insert(0, '.')

from app.screener_scraper import ScreenerScraperService

async def main():
    scraper = ScreenerScraperService()

    ticker = "ANANTRAJ"
    print(f"Fetching quarterly results for {ticker} from Screener.in...")
    print("=" * 60)

    result = await scraper.fetch_quarterly_results(ticker)

    if result.get("success"):
        print(f"Success! Found {len(result.get('results', []))} quarters")
        print()

        for r in result.get("results", [])[:4]:  # Show last 4 quarters
            print(f"--- {r.get('quarterLabel', 'Unknown')} ---")
            print(f"  Revenue: {r.get('revenue')}")
            print(f"  EBITDA:  {r.get('ebitda')}")
            print(f"  PAT:     {r.get('pat')}")
            print(f"  EPS:     {r.get('epsBasic')}")
            print()
    else:
        print(f"Failed: {result.get('error')}")

if __name__ == "__main__":
    asyncio.run(main())
