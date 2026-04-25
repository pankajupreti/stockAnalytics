package com.example.portfolio_service.service;




import com.example.portfolio_service.dto.HoldingDTO;
import com.example.portfolio_service.dto.PortfolioSummaryDTO;
import com.example.portfolio_service.dto.PositionRequest;
import com.example.portfolio_service.dto.QuoteDTO;
import com.example.portfolio_service.model.Position;
import com.example.portfolio_service.model.Transaction;
import com.example.portfolio_service.repository.PositionRepository;
import com.example.portfolio_service.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service

@Transactional(readOnly = true)
public class PortfolioService {

    private final PositionRepository repo;
    private final TransactionRepository transactionRepository;
    private final WebClient reportingWebClient;

    public PortfolioService(PositionRepository repo, TransactionRepository transactionRepository, WebClient reportingWebClient) {
        this.repo = repo;
        this.transactionRepository = transactionRepository;
        this.reportingWebClient = reportingWebClient;
    }

    // ---- CRUD ----
    @Transactional
    public Position create(String userSub, PositionRequest req) {
        String ticker = req.getTicker().toUpperCase(Locale.ROOT).trim();

        Position p = Position.builder()
                .userSub(userSub)
                .ticker(ticker)
                .quantity(req.getQuantity())
                .buyPrice(req.getBuyPrice())
                .buyDate(req.getBuyDate())
                .notes(req.getNotes())
                .build();
        Position saved = repo.save(p);

        // Also create a BUY transaction for tracking capital flow
        Transaction tx = Transaction.builder()
                .userSub(userSub)
                .ticker(ticker)
                .type(Transaction.TransactionType.BUY)
                .quantity(req.getQuantity())
                .price(req.getBuyPrice())
                .transactionDate(req.getBuyDate())
                .positionId(saved.getId())
                .notes(req.getNotes())
                .build();
        transactionRepository.save(tx);

        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<Position> findByIdAndUserSub(Long id, String userSub) {
        return repo.findByIdAndUserSub(id, userSub);
    }

    public List<Position> list(String userSub) {
        return repo.findByUserSubOrderByIdAsc(userSub);
    }

    public Position getOwned(String userSub, Long id) {
        Position p = repo.findById(id).orElseThrow();
        if (!p.getUserSub().equals(userSub)) throw new NoSuchElementException("Not yours");
        return p;
    }

    @Transactional
    public Position update(String userSub, Long id, PositionRequest req) {
        Position p = getOwned(userSub, id);
        p.setTicker(req.getTicker().toUpperCase(Locale.ROOT).trim());
        p.setQuantity(req.getQuantity());
        p.setBuyPrice(req.getBuyPrice());
        p.setBuyDate(req.getBuyDate());
        p.setNotes(req.getNotes());
        return p;
    }

    @Transactional
    public void delete(String userSub, Long id) {
        Position p = getOwned(userSub, id);
        repo.delete(p);
    }

    /**
     * Fix malformed tickers (remove quotes, add NSE: prefix).
     * Returns count of fixed positions.
     */
    @Transactional
    public int fixMalformedTickers(String userSub) {
        List<Position> positions = repo.findByUserSubOrderByIdAsc(userSub);
        int fixed = 0;

        for (Position p : positions) {
            String original = p.getTicker();
            String cleaned = original;

            // Remove surrounding quotes
            cleaned = cleaned.replaceAll("^\"|\"$", "");
            cleaned = cleaned.replaceAll("^'|'$", "");

            // Remove -EQ, -BE, -BL suffixes
            cleaned = cleaned.replaceAll("-EQ$", "")
                    .replaceAll("-BE$", "")
                    .replaceAll("-BL$", "");

            // Add NSE: prefix if missing
            if (!cleaned.contains(":")) {
                cleaned = "NSE:" + cleaned.toUpperCase(Locale.ROOT);
            }

            // Update if changed
            if (!cleaned.equals(original)) {
                p.setTicker(cleaned);
                repo.save(p);
                fixed++;
            }
        }

        return fixed;
    }

    /**
     * Bulk create positions from import (e.g., Zerodha export).
     * Skips positions that already exist for the user (by ticker).
     * Returns count of successfully imported positions.
     */
    @Transactional
    public int bulkCreate(String userSub, List<PositionRequest> requests) {
        if (requests == null || requests.isEmpty()) return 0;

        // Get existing tickers for this user
        Set<String> existingTickers = repo.findByUserSubOrderByIdAsc(userSub).stream()
                .map(p -> p.getTicker().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());

        int imported = 0;
        for (PositionRequest req : requests) {
            String ticker = req.getTicker().toUpperCase(Locale.ROOT).trim();

            // Skip if already exists
            if (existingTickers.contains(ticker)) {
                continue;
            }

            Position p = Position.builder()
                    .userSub(userSub)
                    .ticker(ticker)
                    .quantity(req.getQuantity())
                    .buyPrice(req.getBuyPrice())
                    .buyDate(req.getBuyDate())
                    .notes(req.getNotes())
                    .build();
            repo.save(p);
            existingTickers.add(ticker); // Prevent duplicates in same import
            imported++;
        }

        return imported;
    }

    // ---- Enrichment & summary ----
    public List<HoldingDTO> holdings(String userSub) {
        return holdings(userSub, null);
    }

    public List<HoldingDTO> holdings(String userSub, String jwtToken) {
        List<Position> positions = list(userSub);
        if (positions.isEmpty()) return List.of();

        // Group by ticker to support multiple lots in future
        Map<String, List<Position>> byTicker = positions.stream()
                .collect(Collectors.groupingBy(p -> p.getTicker().toUpperCase(Locale.ROOT)));

        // Fetch quotes once
        String tickersParam = String.join(",",
                byTicker.keySet().stream().sorted().toList());

        // Expecting reporting-service endpoint: GET /api/quotes?tickers=AAPL,MSFT
        var request = reportingWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/quotes")
                        .queryParam("tickers", tickersParam)
                        .build());

        // Add JWT token if provided
        if (jwtToken != null && !jwtToken.isEmpty()) {
            request = request.header("Authorization", "Bearer " + jwtToken);
        }

        List<QuoteDTO> quotes = request
                .retrieve()
                .bodyToFlux(QuoteDTO.class)
                .collectList()
                .blockOptional()
                .orElse(List.of());

        Map<String, QuoteDTO> qmap = quotes.stream()
                .collect(Collectors.toMap(q -> q.getTicker().toUpperCase(Locale.ROOT), q -> q));

        // Build holdings
        List<HoldingDTO> out = new ArrayList<>();
        for (Map.Entry<String, List<Position>> e : byTicker.entrySet()) {
            String ticker = e.getKey();
            int qty = e.getValue().stream().mapToInt(Position::getQuantity).sum();
            BigDecimal buyVal = e.getValue().stream()
                    .map(p -> p.getBuyPrice().multiply(BigDecimal.valueOf(p.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avgBuy = qty == 0 ? BigDecimal.ZERO :
                    buyVal.divide(BigDecimal.valueOf(qty), 4, RoundingMode.HALF_UP);

            QuoteDTO q = qmap.get(ticker);
            // Only use actual market price, don't fall back to buy price
            // This ensures P&L calculations are consistent with frontend
            BigDecimal cmp = (q != null && q.getPrice() != null) ? q.getPrice() : null;

            // If no price available, use buy price for display but mark P&L as null
            BigDecimal displayCmp = cmp != null ? cmp : avgBuy;
            BigDecimal mktVal = cmp != null ? cmp.multiply(BigDecimal.valueOf(qty)) : null;
            BigDecimal pnlAbs = mktVal != null ? mktVal.subtract(buyVal) : null;
            BigDecimal pnlPct = (mktVal != null && buyVal.signum() != 0) ?
                    pnlAbs.multiply(BigDecimal.valueOf(100)).divide(buyVal, 2, RoundingMode.HALF_UP) : null;

            // pick any id (first lot) for edit/delete convenience
            Long id = e.getValue().get(0).getId();

            out.add(HoldingDTO.builder()
                    .id(id)
                    .ticker(ticker)
                    .name(q != null ? q.getName() : null)
                    .quantity(qty)
                    .buyPrice(avgBuy)
                    .buyValue(buyVal)
                    .cmp(displayCmp)
                    .marketValue(mktVal != null ? mktVal : buyVal)
                    .pnlAbs(pnlAbs)
                    .pnlPct(pnlPct)
                    .dailyChange(q != null ? q.getDailyChange() : null)
                    .weeklyChange(q != null ? q.getWeeklyChange() : null)
                    .monthlyChange(q != null ? q.getMonthlyChange() : null)
                    .marketCap(q != null ? q.getMarketCap() : null)
                    .sector(q != null ? q.getSector() : null)
                    .industry(q != null ? q.getIndustry() : null)
                    .high52Week(q != null ? q.getHigh52Week() : null)
                    .low52Week(q != null ? q.getLow52Week() : null)
                    .build());
        }
        // Sort by market value desc
        out.sort(Comparator.comparing(HoldingDTO::getMarketValue).reversed());
        return out;
    }

    public PortfolioSummaryDTO summary(String userSub) {
        return summary(userSub, null);
    }

    public PortfolioSummaryDTO summary(String userSub, String jwtToken) {
        List<HoldingDTO> hs = holdings(userSub, jwtToken);
        var invested = hs.stream()
                .map(HoldingDTO::getBuyValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var current = hs.stream()
                .map(HoldingDTO::getMarketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var pnlAbs = current.subtract(invested);
        var pnlPct = invested.signum()==0 ? BigDecimal.ZERO :
                pnlAbs.multiply(BigDecimal.valueOf(100)).divide(invested, 2, RoundingMode.HALF_UP);

        return PortfolioSummaryDTO.builder()
                .positions(hs.size())
                .invested(invested)
                .current(current)
                .pnlAbs(pnlAbs)
                .pnlPct(pnlPct)
                .build();
    }
}
