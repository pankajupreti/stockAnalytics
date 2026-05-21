package com.example.announcement_service.service;

import com.example.announcement_service.model.TickerMapping;
import com.example.announcement_service.repository.TickerMappingRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service to map BSE scrip codes to NSE/BSE ticker symbols.
 * Uses database as primary source with in-memory cache for performance.
 * Falls back to hardcoded mappings for common stocks if database is empty.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TickerMappingService {

    private final TickerMappingRepository tickerMappingRepository;

    // In-memory cache for fast lookups
    private final Map<String, String> scripToTickerMap = new ConcurrentHashMap<>();
    private final Map<String, String> tickerToScripMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadFromDatabase();

        // If database is empty, initialize with hardcoded NIFTY 50 mappings
        if (scripToTickerMap.isEmpty()) {
            log.info("No mappings in database, initializing with default NIFTY 50 mappings");
            initializeDefaultMappings();
        }

        log.info("Ticker mapping service initialized with {} mappings", scripToTickerMap.size());
    }

    /**
     * Load mappings from database into memory cache
     */
    public void loadFromDatabase() {
        scripToTickerMap.clear();
        tickerToScripMap.clear();

        List<TickerMapping> mappings = tickerMappingRepository.findAll();
        for (TickerMapping mapping : mappings) {
            if (mapping.getScripCode() != null && mapping.getNseTicker() != null) {
                scripToTickerMap.put(mapping.getScripCode(), mapping.getNseTicker().toUpperCase());
                tickerToScripMap.put(mapping.getNseTicker().toUpperCase(), mapping.getScripCode());
            }
        }

        log.info("Loaded {} mappings from database", mappings.size());
    }

    /**
     * Refresh cache from database
     */
    public int refreshCache() {
        loadFromDatabase();
        return scripToTickerMap.size();
    }

    /**
     * Add or update a mapping in both database and cache
     */
    @Transactional
    public TickerMapping saveMapping(String scripCode, String nseTicker, String companyName, String isin) {
        TickerMapping mapping = tickerMappingRepository.findByScripCode(scripCode)
                .orElse(TickerMapping.builder().scripCode(scripCode).build());

        mapping.setNseTicker(nseTicker != null ? nseTicker.toUpperCase() : null);
        mapping.setCompanyName(companyName);
        mapping.setIsin(isin);
        mapping.setActive(true);

        TickerMapping saved = tickerMappingRepository.save(mapping);

        // Update cache
        if (nseTicker != null) {
            scripToTickerMap.put(scripCode, nseTicker.toUpperCase());
            tickerToScripMap.put(nseTicker.toUpperCase(), scripCode);
        }

        return saved;
    }

    /**
     * Bulk save mappings from a list (for CSV import)
     */
    @Transactional
    public int saveMappings(List<TickerMapping> mappings) {
        int count = 0;
        for (TickerMapping mapping : mappings) {
            if (mapping.getScripCode() == null) continue;

            try {
                // Truncate fields to fit database columns
                truncateFields(mapping);

                TickerMapping existing = tickerMappingRepository.findByScripCode(mapping.getScripCode())
                        .orElse(null);

                if (existing != null) {
                    // Update existing
                    if (mapping.getNseTicker() != null) existing.setNseTicker(truncate(mapping.getNseTicker().toUpperCase(), 50));
                    if (mapping.getCompanyName() != null) existing.setCompanyName(truncate(mapping.getCompanyName(), 256));
                    if (mapping.getIsin() != null) existing.setIsin(truncate(mapping.getIsin(), 20));
                    if (mapping.getBseTicker() != null) existing.setBseTicker(truncate(mapping.getBseTicker(), 50));
                    if (mapping.getIndustry() != null) existing.setIndustry(truncate(mapping.getIndustry(), 128));
                    existing.setActive(true);
                    tickerMappingRepository.save(existing);
                } else {
                    // Insert new
                    if (mapping.getNseTicker() != null) {
                        mapping.setNseTicker(mapping.getNseTicker().toUpperCase());
                    }
                    mapping.setActive(true);
                    tickerMappingRepository.save(mapping);
                }
                count++;
            } catch (Exception e) {
                log.warn("Failed to save mapping for scripCode {}: {}", mapping.getScripCode(), e.getMessage());
            }
        }

        // Refresh cache after bulk insert
        loadFromDatabase();
        return count;
    }

    private void truncateFields(TickerMapping mapping) {
        if (mapping.getScripCode() != null) mapping.setScripCode(truncate(mapping.getScripCode(), 20));
        if (mapping.getNseTicker() != null) mapping.setNseTicker(truncate(mapping.getNseTicker(), 50));
        if (mapping.getBseTicker() != null) mapping.setBseTicker(truncate(mapping.getBseTicker(), 50));
        if (mapping.getIsin() != null) mapping.setIsin(truncate(mapping.getIsin(), 20));
        if (mapping.getCompanyName() != null) mapping.setCompanyName(truncate(mapping.getCompanyName(), 256));
        if (mapping.getIndustry() != null) mapping.setIndustry(truncate(mapping.getIndustry(), 128));
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    public void addMapping(String scripCode, String ticker) {
        scripToTickerMap.put(scripCode, ticker.toUpperCase());
        tickerToScripMap.put(ticker.toUpperCase(), scripCode);
    }

    /**
     * Get ticker symbol for a BSE scrip code
     */
    public Optional<String> getTickerForScrip(String scripCode) {
        if (scripCode == null) return Optional.empty();

        // First check cache
        String cached = scripToTickerMap.get(scripCode.trim());
        if (cached != null) {
            return Optional.of(cached);
        }

        // Fallback to database
        return tickerMappingRepository.findByScripCode(scripCode.trim())
                .map(TickerMapping::getNseTicker);
    }

    /**
     * Get BSE scrip code for a ticker symbol
     */
    public Optional<String> getScripForTicker(String ticker) {
        if (ticker == null) return Optional.empty();

        // First check cache
        String cached = tickerToScripMap.get(ticker.toUpperCase().trim());
        if (cached != null) {
            return Optional.of(cached);
        }

        // Fallback to database
        return tickerMappingRepository.findByNseTickerIgnoreCase(ticker.trim())
                .map(TickerMapping::getScripCode);
    }

    /**
     * Resolve ticker - returns the mapped ticker if available, otherwise returns the input
     */
    public String resolveTicker(String scripCode, String fallback) {
        return getTickerForScrip(scripCode).orElse(fallback);
    }

    /**
     * Check if a mapping exists for scrip code
     */
    public boolean hasMappingForScrip(String scripCode) {
        return scripCode != null && (scripToTickerMap.containsKey(scripCode.trim())
                || tickerMappingRepository.existsByScripCode(scripCode.trim()));
    }

    /**
     * Get all known mappings
     */
    public Map<String, String> getAllMappings() {
        return Map.copyOf(scripToTickerMap);
    }

    /**
     * Get count of mappings
     */
    public long getMappingCount() {
        return tickerMappingRepository.count();
    }

    /**
     * Extract pure NSE ticker from a formatted ticker string.
     * Handles formats like "NSE:TCS" or "BSE:532540" and returns the pure ticker.
     * @param formattedTicker The formatted ticker (e.g., "NSE:TCS", "BSE:532540", or just "TCS")
     * @return The pure NSE ticker symbol, or null if cannot be resolved
     */
    public String extractNseTicker(String formattedTicker) {
        if (formattedTicker == null || formattedTicker.isBlank()) {
            return null;
        }

        String ticker = formattedTicker.trim().toUpperCase();

        // If it's in format "NSE:XXX", extract the ticker part
        if (ticker.startsWith("NSE:")) {
            return ticker.substring(4);
        }

        // If it's in format "BSE:XXX" (scrip code), try to map to NSE ticker
        if (ticker.startsWith("BSE:")) {
            String scripCode = ticker.substring(4);
            return getTickerForScrip(scripCode).orElse(null);
        }

        // If it's just a ticker symbol (no prefix), check if it's a known NSE ticker
        if (tickerToScripMap.containsKey(ticker)) {
            return ticker;
        }

        // Check if it looks like a scrip code (all digits)
        if (ticker.matches("\\d+")) {
            return getTickerForScrip(ticker).orElse(null);
        }

        // Return as-is if it looks like a ticker symbol
        return ticker;
    }

    /**
     * Convert a list of portfolio tickers (NSE:XXX format) to pure NSE tickers
     */
    public List<String> extractNseTickers(List<String> formattedTickers) {
        if (formattedTickers == null) {
            return Collections.emptyList();
        }
        return formattedTickers.stream()
                .map(this::extractNseTicker)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Discover and save ticker mappings for scrip codes that have announcements but no mapping.
     * Calls BSE API to resolve each scrip code to an NSE ticker.
     *
     * @param missingScripCodes List of Object[] from findMissingMappings query (scripCode, companyName, count)
     * @param limit Max number of scrip codes to process per run
     * @return Number of newly discovered mappings
     */
    public int discoverMissingMappings(List<Object[]> missingScripCodes, int limit) {
        int discovered = 0;

        for (int i = 0; i < Math.min(missingScripCodes.size(), limit); i++) {
            Object[] row = missingScripCodes.get(i);
            String scripCode = row[0] != null ? row[0].toString() : "";
            String companyName = row[1] != null ? row[1].toString() : "";

            if (scripCode.isEmpty()) continue;

            try {
                String nseTicker = fetchTickerFromBse(scripCode);

                if (nseTicker != null && !nseTicker.isEmpty()) {
                    saveMapping(scripCode, nseTicker, companyName, null);
                    discovered++;
                    log.info("Auto-discovered mapping: {} -> {} ({})", scripCode, nseTicker, companyName);
                }

                // Rate limiting — 300ms between BSE API calls
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("Failed to discover ticker for scripCode {}: {}", scripCode, e.getMessage());
            }
        }

        return discovered;
    }

    /**
     * Fetch NSE ticker from BSE API for a given scrip code.
     * Uses getScripHeaderData endpoint which returns the security ID (ticker symbol).
     */
    public String fetchTickerFromBse(String scripCode) {
        try {
            String apiUrl = "https://api.bseindia.com/BseIndiaAPI/api/getScripHeaderData/w?Ession=blr&scripcode=" + scripCode;

            java.net.URL url = new java.net.URL(apiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Referer", "https://www.bseindia.com/");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return null;
            }

            StringBuilder response = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(response.toString());

            // getScripHeaderData returns Cmpname.ShortN as the BSE security ID (ticker)
            com.fasterxml.jackson.databind.JsonNode cmpname = root.get("Cmpname");
            if (cmpname != null && cmpname.has("ShortN")) {
                String ticker = cmpname.get("ShortN").asText();
                if (ticker != null && !ticker.isBlank()) {
                    return ticker.toUpperCase();
                }
            }

            return null;
        } catch (Exception e) {
            log.debug("Failed to fetch ticker from BSE for {}: {}", scripCode, e.getMessage());
            return null;
        }
    }

    /**
     * Initialize with default NIFTY 50 and Next 50 mappings
     */
    private void initializeDefaultMappings() {
        // NIFTY 50 stocks
        addMapping("500325", "RELIANCE");
        addMapping("532540", "TCS");
        addMapping("500180", "HDFCBANK");
        addMapping("532174", "ICICIBANK");
        addMapping("500209", "INFY");
        addMapping("500112", "SBIN");
        addMapping("532454", "BHARTIARTL");
        addMapping("500696", "HINDUNILVR");
        addMapping("500247", "KOTAKBANK");
        addMapping("532187", "BAJFINANCE");
        addMapping("500034", "BAJAJ-AUTO");
        addMapping("500510", "LT");
        addMapping("500875", "ITC");
        addMapping("532281", "HCLTECH");
        addMapping("507685", "WIPRO");
        addMapping("500820", "ASIANPAINT");
        addMapping("500312", "ONGC");
        addMapping("500010", "HDFC");
        addMapping("532555", "NTPC");
        addMapping("500470", "TATASTEEL");
        addMapping("500570", "TATAMOTORS");
        addMapping("532500", "MARUTI");
        addMapping("500440", "HINDALCO");
        addMapping("500480", "TITAN");
        addMapping("500295", "AXISBANK");
        addMapping("500103", "BPCL");
        addMapping("532898", "POWERGRID");
        addMapping("500124", "DRREDDY");
        addMapping("500182", "HEROMOTOCO");
        addMapping("532978", "BAJAJFINSV");
        addMapping("500483", "ULTRACEMCO");
        addMapping("500400", "TATAPOWER");
        addMapping("500087", "CIPLA");
        addMapping("500790", "NESTLEIND");
        addMapping("500114", "BRITANNIA");
        addMapping("500830", "COLPAL");
        addMapping("500520", "M&M");
        addMapping("532286", "JINDALSTEL");
        addMapping("500940", "TRENT");
        addMapping("532648", "TECHM");

        // Additional popular stocks
        addMapping("500049", "BEL");
        addMapping("532215", "APOLLOHOSP");
        addMapping("539448", "POLYCAB");
        addMapping("543257", "ZOMATO");
        addMapping("500188", "HINDPETRO");
        addMapping("533278", "COALINDIA");
        addMapping("532538", "ADANIENT");
        addMapping("532921", "ADANIPORTS");
        addMapping("500008", "AMBUJACEM");
        addMapping("500410", "ACC");
        addMapping("500302", "GODREJCP");
        addMapping("500260", "GRASIM");
        addMapping("500096", "DABUR");
        addMapping("500488", "VOLTAS");
        addMapping("500459", "BIOCON");
        addMapping("526881", "SIEMENS");
        addMapping("500387", "SHREECEM");
        addMapping("540777", "SBILIFE");
        addMapping("532156", "GAIL");
        addMapping("530965", "IOC");
        addMapping("500331", "BANKBARODA");
        addMapping("543066", "INDIGO");

        log.info("Initialized {} default mappings", scripToTickerMap.size());
    }
}
