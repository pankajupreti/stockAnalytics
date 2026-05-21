package com.example.announcement_service.service;

import com.example.announcement_service.client.BseApiClient;
import com.example.announcement_service.client.PortfolioClient;
import com.example.announcement_service.client.ResultsServiceClient;
import com.example.announcement_service.dto.AnnouncementDTO;
import com.example.announcement_service.dto.BseAnnouncementResponse;
import com.example.announcement_service.dto.PortfolioAnnouncementDTO;
import com.example.announcement_service.model.Announcement;
import com.example.announcement_service.repository.AnnouncementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AnnouncementService {

    private final AnnouncementRepository repository;
    private final BseApiClient bseApiClient;
    private final PortfolioClient portfolioClient;
    private final TickerMappingService tickerMappingService;
    private final ResultsServiceClient resultsServiceClient;
    private final ResultsEventPublisher resultsEventPublisher;
    private final AnnouncementPersistenceService persistenceService;

    @Autowired
    @Lazy
    private ResultsService resultsService;

    private static final DateTimeFormatter BSE_DATETIME_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss");
    private static final DateTimeFormatter BSE_DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /**
     * Get all announcements with pagination
     */
    public Page<AnnouncementDTO> getAnnouncements(String category, LocalDate fromDate, LocalDate toDate,
                                                   int page, int size) {
        LocalDateTime from = fromDate != null ? fromDate.atStartOfDay() : LocalDate.now().minusDays(7).atStartOfDay();
        LocalDateTime to = toDate != null ? toDate.atTime(23, 59, 59) : LocalDate.now().atTime(23, 59, 59);

        Pageable pageable = PageRequest.of(page, size);
        Page<Announcement> announcements = repository.findByFilters(category, from, to, pageable);

        return announcements.map(this::toDTO);
    }

    /**
     * Get announcements for specific tickers.
     * Supports both NSE format (NSE:TCS) and pure ticker format (TCS).
     * Also searches by company name as a fallback for stocks without ticker mappings.
     */
    public List<AnnouncementDTO> getAnnouncementsByTickers(List<String> tickers, int days) {
        if (tickers == null || tickers.isEmpty()) {
            return Collections.emptyList();
        }

        // Extract pure NSE tickers from formatted input (e.g., "NSE:TCS" -> "TCS")
        List<String> nseTickers = tickerMappingService.extractNseTickers(tickers);

        LocalDateTime afterDate = LocalDateTime.now().minusDays(days);

        // First try: search by nseTicker field
        List<Announcement> announcements = new ArrayList<>();
        if (!nseTickers.isEmpty()) {
            announcements.addAll(repository.findByNseTickersInAndAfterDate(nseTickers, afterDate));
        }

        // Second try: if no results, search by company name (useful for stocks like SWIGGY)
        if (announcements.isEmpty() && !nseTickers.isEmpty()) {
            for (String ticker : nseTickers) {
                List<Announcement> byCompanyName = repository.findByCompanyNameContainingAndAfterDate(ticker, afterDate);
                announcements.addAll(byCompanyName);
            }
        }

        // Remove duplicates and sort by date
        return announcements.stream()
                .collect(Collectors.toMap(
                        Announcement::getNewsId,
                        a -> a,
                        (a1, a2) -> a1
                ))
                .values()
                .stream()
                .sorted(Comparator.comparing(Announcement::getAnnouncementDate).reversed())
                .map(this::toDTO)
                .toList();
    }

    /**
     * Get announcements grouped by ticker for portfolio view.
     * Handles NSE:XXX ticker format from portfolio service.
     */
    @Cacheable(value = "portfolioAnnouncements", key = "#bearerToken.hashCode()")
    public List<PortfolioAnnouncementDTO> getPortfolioAnnouncements(String bearerToken, int days, int maxPerTicker) {
        // Fetch user's portfolio tickers (in NSE:XXX format)
        List<String> portfolioTickers = portfolioClient.fetchUserTickers(bearerToken)
                .blockOptional()
                .orElse(Collections.emptyList());

        if (portfolioTickers.isEmpty()) {
            log.debug("No tickers in user portfolio");
            return Collections.emptyList();
        }

        // Extract pure NSE tickers (e.g., "NSE:TCS" -> "TCS")
        List<String> nseTickers = tickerMappingService.extractNseTickers(portfolioTickers);

        if (nseTickers.isEmpty()) {
            log.debug("No valid NSE tickers resolved from portfolio");
            return Collections.emptyList();
        }

        LocalDateTime afterDate = LocalDateTime.now().minusDays(days);
        List<Announcement> announcements = repository.findByNseTickersInAndAfterDate(nseTickers, afterDate);

        // Group by NSE ticker
        Map<String, List<Announcement>> grouped = announcements.stream()
                .filter(a -> a.getNseTicker() != null)
                .collect(Collectors.groupingBy(a -> a.getNseTicker().toUpperCase()));

        return grouped.entrySet().stream()
                .map(entry -> {
                    String nseTicker = entry.getKey();
                    List<Announcement> tickerAnnouncements = entry.getValue();
                    String companyName = tickerAnnouncements.isEmpty() ? nseTicker :
                            tickerAnnouncements.get(0).getCompanyName();

                    List<AnnouncementDTO> recentAnnouncements = tickerAnnouncements.stream()
                            .sorted(Comparator.comparing(Announcement::getAnnouncementDate).reversed())
                            .limit(maxPerTicker)
                            .map(this::toDTO)
                            .toList();

                    return PortfolioAnnouncementDTO.builder()
                            .ticker("NSE:" + nseTicker)  // Return in NSE:XXX format for frontend
                            .companyName(companyName)
                            .announcementCount(tickerAnnouncements.size())
                            .recentAnnouncements(recentAnnouncements)
                            .build();
                })
                .sorted(Comparator.comparingInt(PortfolioAnnouncementDTO::getAnnouncementCount).reversed())
                .toList();
    }

    /**
     * Get announcement by ID
     */
    public Optional<AnnouncementDTO> getAnnouncementById(Long id) {
        return repository.findById(id).map(this::toDTO);
    }

    /**
     * Get distinct categories
     */
    public List<String> getCategories() {
        return repository.findDistinctCategories();
    }

    /**
     * Check which tickers from a list have recent announcements.
     * Supports NSE format (NSE:TCS) and returns results with original ticker keys.
     */
    public Map<String, Boolean> checkTickersForAnnouncements(List<String> tickers, int days) {
        if (tickers == null || tickers.isEmpty()) {
            return Collections.emptyMap();
        }

        LocalDateTime afterDate = LocalDateTime.now().minusDays(days);
        List<String> nseTickersWithAnnouncements = repository.findNseTickersWithRecentAnnouncements(afterDate);
        Set<String> announcementSet = new HashSet<>(nseTickersWithAnnouncements);

        // Map original ticker format to boolean result
        return tickers.stream()
                .collect(Collectors.toMap(
                        ticker -> ticker,
                        ticker -> {
                            String nseTicker = tickerMappingService.extractNseTicker(ticker);
                            return nseTicker != null && announcementSet.contains(nseTicker);
                        }
                ));
    }

    /**
     * Get announcement counts for specific tickers.
     * Supports NSE format (NSE:TCS) and returns counts with original ticker keys.
     */
    public Map<String, Integer> getAnnouncementCounts(List<String> tickers, int days) {
        if (tickers == null || tickers.isEmpty()) {
            return Collections.emptyMap();
        }

        // Extract pure NSE tickers
        List<String> nseTickers = tickerMappingService.extractNseTickers(tickers);

        if (nseTickers.isEmpty()) {
            return tickers.stream().collect(Collectors.toMap(t -> t, t -> 0));
        }

        LocalDateTime afterDate = LocalDateTime.now().minusDays(days);
        List<Announcement> announcements = repository.findByNseTickersInAndAfterDate(nseTickers, afterDate);

        // Count announcements by NSE ticker
        Map<String, Long> countsByNseTicker = announcements.stream()
                .filter(a -> a.getNseTicker() != null)
                .collect(Collectors.groupingBy(
                        a -> a.getNseTicker().toUpperCase(),
                        Collectors.counting()
                ));

        // Map back to original ticker format
        return tickers.stream()
                .collect(Collectors.toMap(
                        ticker -> ticker,
                        ticker -> {
                            String nseTicker = tickerMappingService.extractNseTicker(ticker);
                            if (nseTicker == null) return 0;
                            return countsByNseTicker.getOrDefault(nseTicker.toUpperCase(), 0L).intValue();
                        }
                ));
    }

    /**
     * Fetch announcements LIVE from BSE API for a specific ticker.
     * This is triggered by user search - fetches real-time data and saves to DB.
     *
     * @param ticker The ticker to search (e.g., "TCS", "NSE:TCS", "RELIANCE")
     * @param days Number of days to look back
     * @return List of announcements (fresh from BSE + cached from DB)
     */
    @Transactional
    public List<AnnouncementDTO> fetchLiveAnnouncements(String ticker, int days) {
        if (ticker == null || ticker.isBlank()) {
            return Collections.emptyList();
        }

        String nseTicker = tickerMappingService.extractNseTicker(ticker);
        if (nseTicker == null) {
            nseTicker = ticker.toUpperCase();
        }

        log.info("Live fetch for ticker: {} (resolved: {})", ticker, nseTicker);

        LocalDate toDate = LocalDate.now();
        LocalDate fromDate = toDate.minusDays(days);
        List<AnnouncementDTO> results = new ArrayList<>();

        // Step 1: Try to get scrip code for this ticker
        Optional<String> scripCodeOpt = tickerMappingService.getScripForTicker(nseTicker);

        if (scripCodeOpt.isPresent()) {
            // We have a scrip code - fetch directly from BSE
            String scripCode = scripCodeOpt.get();
            log.info("Found scrip code {} for ticker {}, fetching from BSE", scripCode, nseTicker);

            try {
                BseAnnouncementResponse response = bseApiClient.fetchAnnouncementsByScripCode(scripCode, fromDate, toDate)
                        .block();

                if (response != null && response.getTable() != null) {
                    int savedCount = 0;
                    for (BseAnnouncementResponse.BseAnnouncement bseAnn : response.getTable()) {
                        // Save to DB if not exists (using safe method)
                        if (bseAnn.getNewsId() != null) {
                            try {
                                Announcement announcement = mapFromBseAnnouncement(bseAnn);
                                // Set nseTicker since we know it
                                announcement.setNseTicker(nseTicker);
                                Announcement saved = persistenceService.saveAnnouncementSafely(announcement);
                                if (saved != null) {
                                    savedCount++;
                                    // Trigger results parsing if this is a financial result
                                    triggerResultsParsingIfNeeded(saved);
                                }
                            } catch (Exception e) {
                                log.warn("Failed to save announcement {}: {}", bseAnn.getNewsId(), e.getMessage());
                            }
                        }

                        // Add to results
                        results.add(mapBseToDTO(bseAnn, nseTicker));
                    }
                    log.info("Live fetch for {}: got {} announcements, saved {} new", nseTicker, response.getTable().size(), savedCount);
                }
            } catch (Exception e) {
                log.error("Error fetching live announcements for {}: {}", nseTicker, e.getMessage());
            }
        } else {
            // No scrip code mapping - try searching by company name in BSE API
            log.info("No scrip code for ticker {}, trying company name search", nseTicker);

            try {
                // Fetch all recent announcements and filter by company name
                BseAnnouncementResponse response = bseApiClient.fetchAnnouncements(fromDate, toDate, null)
                        .block();

                if (response != null && response.getTable() != null) {
                    String searchTerm = nseTicker.toLowerCase();
                    int savedCount = 0;

                    for (BseAnnouncementResponse.BseAnnouncement bseAnn : response.getTable()) {
                        String companyName = bseAnn.getCompanyName();
                        if (companyName != null && companyName.toLowerCase().contains(searchTerm)) {
                            // Save to DB if not exists (using safe method)
                            if (bseAnn.getNewsId() != null) {
                                try {
                                    Announcement announcement = mapFromBseAnnouncement(bseAnn);
                                    announcement.setNseTicker(nseTicker);
                                    Announcement saved = persistenceService.saveAnnouncementSafely(announcement);
                                    if (saved != null) {
                                        savedCount++;

                                        // Also create a mapping for future use
                                        if (bseAnn.getScripCode() != null) {
                                            tickerMappingService.addMapping(bseAnn.getScripCode(), nseTicker);
                                        }

                                        // Trigger results parsing if this is a financial result
                                        triggerResultsParsingIfNeeded(saved);
                                    }
                                } catch (Exception e) {
                                    log.warn("Failed to save announcement {}: {}", bseAnn.getNewsId(), e.getMessage());
                                }
                            }

                            results.add(mapBseToDTO(bseAnn, nseTicker));
                        }
                    }
                    log.info("Company name search for {}: found {} matches, saved {} new", nseTicker, results.size(), savedCount);
                }
            } catch (Exception e) {
                log.error("Error in company name search for {}: {}", nseTicker, e.getMessage());
            }
        }

        // Also include any cached results from DB
        LocalDateTime afterDate = LocalDateTime.now().minusDays(days);
        List<Announcement> cachedAnnouncements = repository.findByNseTickersInAndAfterDate(
                List.of(nseTicker), afterDate);

        // Merge cached results (avoiding duplicates by newsId)
        Set<String> resultNewsIds = results.stream()
                .map(AnnouncementDTO::getNewsId)
                .collect(Collectors.toSet());

        for (Announcement cached : cachedAnnouncements) {
            if (!resultNewsIds.contains(cached.getNewsId())) {
                results.add(toDTO(cached));
            }
        }

        // Sort by date descending
        results.sort(Comparator.comparing(AnnouncementDTO::getAnnouncementDate).reversed());

        return results;
    }

    /**
     * Map BSE announcement directly to DTO (for live results before DB save)
     */
    private AnnouncementDTO mapBseToDTO(BseAnnouncementResponse.BseAnnouncement bse, String nseTicker) {
        LocalDateTime announcementDate = parseDateTime(bse.getNewsDate(), bse.getSessionTime(), bse.getDateTime());

        return AnnouncementDTO.builder()
                .newsId(bse.getNewsId())
                .scripCode(bse.getScripCode())
                .ticker("BSE:" + bse.getScripCode())
                .nseTicker(nseTicker)
                .companyName(bse.getCompanyName() != null ? bse.getCompanyName().trim() : "")
                .subject(bse.getSubject() != null ? bse.getSubject().trim() : "")
                .category(bse.getCategory())
                .subCategory(bse.getSubCategory())
                .announcementDate(announcementDate)
                .broadcastDateTime(bse.getDateTime())
                .pdfUrl(bseApiClient.buildPdfUrl(bse.getNewsId(), bse.getAttachmentName()))
                .build();
    }

    /**
     * Sync announcements from BSE API
     */
    @Transactional
    public int syncAnnouncements(LocalDate fromDate, LocalDate toDate, String category) {
        log.info("Starting announcement sync from {} to {}", fromDate, toDate);

        BseAnnouncementResponse response = bseApiClient.fetchAnnouncements(fromDate, toDate, category)
                .block();

        if (response == null || response.getTable() == null) {
            log.warn("No announcements received from BSE API");
            return 0;
        }

        int savedCount = 0;
        for (BseAnnouncementResponse.BseAnnouncement bseAnn : response.getTable()) {
            if (bseAnn.getNewsId() == null) {
                continue;
            }

            try {
                Announcement announcement = mapFromBseAnnouncement(bseAnn);
                Announcement saved = persistenceService.saveAnnouncementSafely(announcement);
                if (saved != null) {
                    savedCount++;
                    // Trigger results parsing if this is a financial result
                    triggerResultsParsingIfNeeded(saved);
                }
            } catch (Exception e) {
                log.warn("Failed to save announcement {}: {}", bseAnn.getNewsId(), e.getMessage());
            }
        }

        log.info("Saved {} new announcements", savedCount);
        return savedCount;
    }

    private Announcement mapFromBseAnnouncement(BseAnnouncementResponse.BseAnnouncement bse) {
        LocalDateTime announcementDate = parseDateTime(bse.getNewsDate(), bse.getSessionTime(), bse.getDateTime());

        // Extract ticker from company name or use scrip code as fallback
        String ticker = extractTicker(bse.getCompanyName(), bse.getScripCode());

        // Resolve NSE ticker from BSE scrip code
        String nseTicker = tickerMappingService.getTickerForScrip(bse.getScripCode()).orElse(null);

        return Announcement.builder()
                .newsId(bse.getNewsId())
                .scripCode(bse.getScripCode())
                .ticker(ticker)
                .nseTicker(nseTicker)
                .companyName(bse.getCompanyName() != null ? bse.getCompanyName().trim() : "")
                .subject(bse.getSubject() != null ? bse.getSubject().trim() : "")
                .category(bse.getCategory())
                .subCategory(bse.getSubCategory())
                .announcementDate(announcementDate)
                .pdfUrl(bseApiClient.buildPdfUrl(bse.getNewsId(), bse.getAttachmentName()))
                .broadcastDateTime(bse.getDateTime())
                .build();
    }

    private LocalDateTime parseDateTime(String dateStr, String timeStr, String broadcastDateTime) {
        try {
            if (dateStr != null && timeStr != null) {
                String combined = dateStr.trim() + " " + timeStr.trim();
                return LocalDateTime.parse(combined, BSE_DATETIME_FORMAT);
            } else if (dateStr != null) {
                return LocalDate.parse(dateStr.trim(), BSE_DATE_FORMAT).atStartOfDay();
            }
        } catch (Exception e) {
            log.debug("Failed to parse date: {} {}", dateStr, timeStr);
        }
        // Fallback: parse broadcastDateTime (ISO format like "2026-04-30T13:05:32.6")
        if (broadcastDateTime != null) {
            try {
                return LocalDateTime.parse(broadcastDateTime.trim());
            } catch (Exception e) {
                log.debug("Failed to parse broadcastDateTime: {}", broadcastDateTime);
            }
        }
        return LocalDateTime.now();
    }

    private String extractTicker(String companyName, String scripCode) {
        // BSE typically uses scrip codes, we need to map to NSE tickers
        // For now, use scrip code as ticker; this can be enhanced with a mapping table
        if (scripCode != null && !scripCode.isBlank()) {
            return "BSE:" + scripCode.trim();
        }
        // Fallback: use first word of company name
        if (companyName != null && !companyName.isBlank()) {
            String[] parts = companyName.trim().split("\\s+");
            return parts[0].toUpperCase();
        }
        return "UNKNOWN";
    }

    /**
     * Update announcements that have null nseTicker with mapped values.
     * Useful for fixing announcements that were synced before mappings were created.
     */
    @Transactional
    public int updateMissingNseTickers() {
        List<Announcement> announcementsWithoutNseTicker = repository.findAll().stream()
                .filter(a -> a.getNseTicker() == null && a.getScripCode() != null)
                .toList();

        int updatedCount = 0;
        for (Announcement ann : announcementsWithoutNseTicker) {
            Optional<String> nseTicker = tickerMappingService.getTickerForScrip(ann.getScripCode());
            if (nseTicker.isPresent()) {
                ann.setNseTicker(nseTicker.get());
                repository.save(ann);
                updatedCount++;
            }
        }

        if (updatedCount > 0) {
            log.info("Updated {} announcements with missing nseTicker", updatedCount);
        }
        return updatedCount;
    }

    private AnnouncementDTO toDTO(Announcement a) {
        return AnnouncementDTO.builder()
                .id(a.getId())
                .scripCode(a.getScripCode())
                .ticker(a.getTicker())
                .nseTicker(a.getNseTicker())
                .companyName(a.getCompanyName())
                .subject(a.getSubject())
                .category(a.getCategory())
                .subCategory(a.getSubCategory())
                .announcementDate(a.getAnnouncementDate())
                .broadcastDateTime(a.getBroadcastDateTime())
                .pdfUrl(a.getPdfUrl())
                .newsId(a.getNewsId())
                .seen(a.getSeen() != null ? a.getSeen() : false)
                .seenAt(a.getSeenAt())
                .build();
    }

    // ==================== SEEN/UNSEEN STATUS METHODS ====================

    /**
     * Mark announcements as seen by the user.
     * @param userId User ID from JWT
     * @param newsIds List of announcement newsIds to mark as seen
     * @return Number of announcements marked as seen
     */
    @Transactional
    public int markAnnouncementsAsSeen(String userId, List<String> newsIds) {
        if (newsIds == null || newsIds.isEmpty()) {
            return 0;
        }

        List<Announcement> announcements = repository.findByNewsIds(newsIds);
        int count = 0;
        LocalDateTime now = LocalDateTime.now();

        for (Announcement a : announcements) {
            // Only mark as seen if not already seen by this user
            if (a.getSeen() == null || !a.getSeen()) {
                a.setSeen(true);
                a.setSeenAt(now);
                a.setUserId(userId);
                repository.save(a);
                count++;
            }
        }

        log.info("Marked {} announcements as seen for user {}", count, userId);
        return count;
    }

    /**
     * Mark announcements as seen by IDs.
     */
    @Transactional
    public int markAnnouncementsAsSeenByIds(String userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }

        List<Announcement> announcements = repository.findByIdIn(ids);
        int count = 0;
        LocalDateTime now = LocalDateTime.now();

        for (Announcement a : announcements) {
            if (a.getSeen() == null || !a.getSeen()) {
                a.setSeen(true);
                a.setSeenAt(now);
                a.setUserId(userId);
                repository.save(a);
                count++;
            }
        }

        log.info("Marked {} announcements as seen by IDs for user {}", count, userId);
        return count;
    }

    /**
     * Get unseen announcement count for user's portfolio tickers.
     */
    public long getUnseenCount(String userId, List<String> tickers, int days) {
        if (tickers == null || tickers.isEmpty()) {
            return 0;
        }

        List<String> nseTickers = tickerMappingService.extractNseTickers(tickers);
        if (nseTickers.isEmpty()) {
            return 0;
        }

        LocalDateTime afterDate = LocalDateTime.now().minusDays(days);
        return repository.countUnseenByUserAndNseTickers(userId, nseTickers, afterDate);
    }

    /**
     * Get unseen announcement counts per ticker.
     * Returns a map of original ticker -> unseen count.
     */
    public Map<String, Integer> getUnseenCounts(String userId, List<String> tickers, int days) {
        if (tickers == null || tickers.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> nseTickers = tickerMappingService.extractNseTickers(tickers);
        if (nseTickers.isEmpty()) {
            return tickers.stream().collect(Collectors.toMap(t -> t, t -> 0));
        }

        LocalDateTime afterDate = LocalDateTime.now().minusDays(days);
        List<Object[]> results = repository.countUnseenPerTickerByUser(userId, nseTickers, afterDate);

        // Build map of nseTicker -> count
        Map<String, Long> countsByNseTicker = new HashMap<>();
        for (Object[] row : results) {
            String nseTicker = row[0] != null ? row[0].toString().toUpperCase() : null;
            Long count = row[1] != null ? (Long) row[1] : 0L;
            if (nseTicker != null) {
                countsByNseTicker.put(nseTicker, count);
            }
        }

        // Map back to original ticker format
        return tickers.stream()
                .collect(Collectors.toMap(
                        ticker -> ticker,
                        ticker -> {
                            String nseTicker = tickerMappingService.extractNseTicker(ticker);
                            if (nseTicker == null) return 0;
                            return countsByNseTicker.getOrDefault(nseTicker.toUpperCase(), 0L).intValue();
                        }
                ));
    }

    /**
     * Mark all announcements for given tickers as seen.
     */
    @Transactional
    public int markAllAsSeenForTickers(String userId, List<String> tickers, int days) {
        if (tickers == null || tickers.isEmpty()) {
            return 0;
        }

        List<String> nseTickers = tickerMappingService.extractNseTickers(tickers);
        if (nseTickers.isEmpty()) {
            return 0;
        }

        LocalDateTime afterDate = LocalDateTime.now().minusDays(days);
        List<Announcement> unseen = repository.findUnseenByUserAndNseTickers(userId, nseTickers, afterDate);

        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        for (Announcement a : unseen) {
            a.setSeen(true);
            a.setSeenAt(now);
            a.setUserId(userId);
            repository.save(a);
            count++;
        }

        log.info("Marked all {} announcements as seen for tickers {} by user {}", count, tickers, userId);
        return count;
    }

    /**
     * Search companies for autocomplete suggestions.
     * Returns list of companies matching the query with their ticker and scrip code.
     */
    public List<Map<String, String>> searchCompanies(String query) {
        List<Object[]> results = repository.searchCompanies(query);

        // Use a map to deduplicate by company name
        Map<String, Map<String, String>> uniqueCompanies = new LinkedHashMap<>();

        for (Object[] row : results) {
            String companyName = row[0] != null ? row[0].toString().trim() : "";
            String nseTicker = row[1] != null ? row[1].toString().trim() : "";
            String scripCode = row[2] != null ? row[2].toString().trim() : "";

            if (companyName.isEmpty()) continue;

            // Use company name as key to avoid duplicates
            String key = companyName.toUpperCase();
            if (!uniqueCompanies.containsKey(key)) {
                Map<String, String> company = new LinkedHashMap<>();
                company.put("companyName", companyName);
                company.put("nseTicker", nseTicker);
                company.put("scripCode", scripCode);
                // Create display label
                String label = companyName;
                if (!nseTicker.isEmpty()) {
                    label += " (" + nseTicker + ")";
                } else if (!scripCode.isEmpty()) {
                    label += " (BSE:" + scripCode + ")";
                }
                company.put("label", label);
                uniqueCompanies.put(key, company);
            }
        }

        // Return top 15 results
        return uniqueCompanies.values().stream()
                .limit(15)
                .toList();
    }

    // ==================== FINANCIAL RESULTS PARSING ====================

    /**
     * Keywords that indicate a financial results announcement.
     */
    private static final String[] FINANCIAL_RESULT_KEYWORDS = {
            "financial result",
            "quarterly result",
            "un-audited financial",
            "unaudited financial",
            "audited financial",
            "standalone financial",
            "consolidated financial",
            "outcome of board meeting",
            "outcome of the board meeting",
            "board meeting outcome",
            "board meeting",  // Catches "Board Meeting NEWS" etc.
            "results for the quarter",
            "results for quarter"
    };

    /**
     * Check if an announcement is a financial result based on subject/category.
     */
    private boolean isFinancialResultAnnouncement(BseAnnouncementResponse.BseAnnouncement bseAnn) {
        String subject = bseAnn.getSubject() != null ? bseAnn.getSubject().toLowerCase() : "";
        String category = bseAnn.getCategory() != null ? bseAnn.getCategory().toLowerCase() : "";

        // Direct category match - "Result" category is always a financial result
        if (category.equals("result")) {
            return true;
        }

        for (String keyword : FINANCIAL_RESULT_KEYWORDS) {
            if (subject.contains(keyword) || category.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Trigger parsing of financial results from PDF.
     * Called asynchronously after saving a financial result announcement.
     */
    private void triggerResultsParsingIfNeeded(Announcement announcement) {
        if (announcement == null || announcement.getPdfUrl() == null) {
            return;
        }

        String subject = announcement.getSubject() != null ? announcement.getSubject().toLowerCase() : "";
        String category = announcement.getCategory() != null ? announcement.getCategory().toLowerCase() : "";

        // Direct category match - "Result" category is always a financial result
        boolean isFinancialResult = category.equals("result");

        if (!isFinancialResult) {
            for (String keyword : FINANCIAL_RESULT_KEYWORDS) {
                if (subject.contains(keyword) || category.contains(keyword)) {
                    isFinancialResult = true;
                    break;
                }
            }
        }

        if (isFinancialResult) {
            String tickerValue = announcement.getNseTicker();
            if (tickerValue == null || tickerValue.isBlank()) {
                // Try to extract from company name
                tickerValue = extractTickerFromCompanyName(announcement.getCompanyName());
            }

            if (tickerValue != null && !tickerValue.isBlank()) {
                final String ticker = tickerValue;
                log.info("Detected financial result for {}, triggering parsing", ticker);

                // 1. Try PDF parsing first (announcement-service internal)
                try {
                    var result = resultsService.triggerParsing(ticker, announcement.getPdfUrl(), announcement.getId());
                    if (result != null) {
                        log.info("PDF parsing completed for {}: {}", ticker, result.getQuarterLabel());
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse results PDF for {}: {}", ticker, e.getMessage());
                }

                // 2. Also trigger Screener refresh for Python results-service cache
                // This ensures data is ready when user views results
                try {
                    resultsServiceClient.triggerScreenerRefresh(ticker)
                            .subscribe(
                                    success -> {
                                        if (success) {
                                            log.info("Screener refresh triggered for {}", ticker);
                                        }
                                    },
                                    error -> log.debug("Screener refresh failed for {}: {}", ticker, error.getMessage())
                            );
                } catch (Exception e) {
                    log.debug("Could not trigger Screener refresh for {}: {}", ticker, e.getMessage());
                }

                // 3. Publish to RabbitMQ for async retry-based fetching
                // The Python consumer will retry every 6 hours until Screener has data
                try {
                    resultsEventPublisher.publishResultsFetchEvent(announcement, ticker);
                } catch (Exception e) {
                    log.warn("Failed to publish RabbitMQ event for {}: {}", ticker, e.getMessage());
                }
            }
        }
    }

    /**
     * Extract a ticker symbol from company name.
     */
    private String extractTickerFromCompanyName(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return null;
        }
        // Take first word and uppercase it as a basic ticker
        String[] parts = companyName.trim().split("\\s+");
        if (parts.length > 0) {
            return parts[0].toUpperCase().replaceAll("[^A-Z]", "");
        }
        return null;
    }
}
