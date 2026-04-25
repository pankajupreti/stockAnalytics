package com.example.announcement_service.service;

import com.example.announcement_service.model.TickerMapping;
import com.example.announcement_service.repository.TickerMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service to match stock analytics tickers with BSE scrip codes.
 * Simple matching: BSE Security Id = NSE ticker (after removing "NSE:" prefix)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockMatchingService {

    private final TickerMappingRepository tickerMappingRepository;
    private final TickerMappingService tickerMappingService;

    /**
     * Match stock analytics tickers with BSE equity list.
     *
     * Simple logic:
     * - Stock analytics ticker: "NSE:RELIANCE" -> extract "RELIANCE"
     * - BSE Security Id: "RELIANCE"
     * - If they match, create mapping with BSE Security Code (scrip code)
     *
     * @param stockAnalyticsList List of maps with keys: ticker (e.g., "NSE:RELIANCE")
     * @param bseEquityList List of maps with keys: scripCode, securityId, companyName, isin
     * @return MatchResult with counts
     */
    @Transactional
    public MatchResult matchStocks(List<Map<String, String>> stockAnalyticsList,
                                    List<Map<String, String>> bseEquityList) {

        log.info("Starting stock matching: {} stock analytics, {} BSE stocks",
                stockAnalyticsList.size(), bseEquityList.size());

        // Build lookup map: Security Id (uppercase) -> BSE record
        Map<String, Map<String, String>> bseBySecurityId = new HashMap<>();
        for (Map<String, String> bse : bseEquityList) {
            String securityId = bse.get("securityId");
            if (securityId != null && !securityId.isBlank()) {
                bseBySecurityId.put(securityId.trim().toUpperCase(), bse);
            }
        }

        int matched = 0;
        int noMatch = 0;
        List<TickerMapping> mappingsToSave = new ArrayList<>();

        for (Map<String, String> stock : stockAnalyticsList) {
            String rawTicker = stock.get("ticker");
            if (rawTicker == null || rawTicker.isBlank()) continue;

            // Extract pure ticker: "NSE:RELIANCE" -> "RELIANCE"
            String nseTicker = extractPureTicker(rawTicker);
            if (nseTicker.isEmpty()) continue;

            // Look up in BSE data by Security Id
            Map<String, String> bseMatch = bseBySecurityId.get(nseTicker);

            if (bseMatch != null) {
                matched++;
                mappingsToSave.add(TickerMapping.builder()
                        .scripCode(bseMatch.get("scripCode"))
                        .nseTicker(nseTicker)
                        .bseTicker(bseMatch.get("securityId"))
                        .companyName(bseMatch.get("companyName"))
                        .isin(bseMatch.get("isin"))
                        .active(true)
                        .build());
            } else {
                noMatch++;
                log.debug("No BSE match for ticker: {}", nseTicker);
            }
        }

        // Batch save
        int savedCount = tickerMappingService.saveMappings(mappingsToSave);

        log.info("Matching complete: {} matched, {} no match, {} saved",
                matched, noMatch, savedCount);

        return new MatchResult(matched, noMatch, savedCount);
    }

    /**
     * Extract pure ticker from formatted string.
     * "NSE:RELIANCE" -> "RELIANCE"
     * "BSE:500325" -> "500325"
     * "RELIANCE" -> "RELIANCE"
     */
    private String extractPureTicker(String ticker) {
        if (ticker == null) return "";
        ticker = ticker.trim().toUpperCase();

        if (ticker.startsWith("NSE:")) {
            return ticker.substring(4);
        }
        if (ticker.startsWith("BSE:")) {
            return ticker.substring(4);
        }
        return ticker;
    }

    /**
     * Result of the matching operation.
     */
    public record MatchResult(int matched, int noMatch, int savedCount) {}
}
