package com.example.portfolio_service.service;




import com.example.portfolio_service.dto.HoldingDTO;
import com.example.portfolio_service.dto.PortfolioSummaryDTO;
import com.example.portfolio_service.dto.PositionRequest;
import com.example.portfolio_service.dto.QuoteDTO;
import com.example.portfolio_service.model.Position;
import com.example.portfolio_service.repository.PositionRepository;
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
    private final WebClient reportingWebClient;

    public PortfolioService(PositionRepository repo, WebClient reportingWebClient) {
        this.repo = repo;
        this.reportingWebClient = reportingWebClient;
    }

    // ---- CRUD ----
    @Transactional
    public Position create(String userSub, PositionRequest req) {
        Position p = Position.builder()
                .userSub(userSub)
                .ticker(req.getTicker().toUpperCase(Locale.ROOT).trim())
                .quantity(req.getQuantity())
                .buyPrice(req.getBuyPrice())
                .buyDate(req.getBuyDate())
                .notes(req.getNotes())
                .build();
        return repo.save(p);
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

    // ---- Enrichment & summary ----
    public List<HoldingDTO> holdings(String userSub) {
        List<Position> positions = list(userSub);
        if (positions.isEmpty()) return List.of();

        // Group by ticker to support multiple lots in future
        Map<String, List<Position>> byTicker = positions.stream()
                .collect(Collectors.groupingBy(p -> p.getTicker().toUpperCase(Locale.ROOT)));

        // Fetch quotes once
        String tickersParam = String.join(",",
                byTicker.keySet().stream().sorted().toList());

        // Expecting reporting-service endpoint: GET /api/quotes?tickers=AAPL,MSFT
        List<QuoteDTO> quotes = reportingWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/quotes")
                        .queryParam("tickers", tickersParam)
                        .build())
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
            BigDecimal cmp = (q != null && q.getPrice() != null) ? q.getPrice() : avgBuy;

            BigDecimal mktVal = cmp.multiply(BigDecimal.valueOf(qty));
            BigDecimal pnlAbs = mktVal.subtract(buyVal);
            BigDecimal pnlPct = buyVal.signum()==0 ? BigDecimal.ZERO :
                    pnlAbs.multiply(BigDecimal.valueOf(100)).divide(buyVal, 2, RoundingMode.HALF_UP);

            // pick any id (first lot) for edit/delete convenience
            Long id = e.getValue().get(0).getId();

            out.add(HoldingDTO.builder()
                    .id(id)
                    .ticker(ticker)
                    .quantity(qty)
                    .buyPrice(avgBuy)
                    .buyValue(buyVal)
                    .cmp(cmp)
                    .marketValue(mktVal)
                    .pnlAbs(pnlAbs)
                    .pnlPct(pnlPct)
                    .dailyChange(q != null ? q.getDailyChange() : null)
                    .weeklyChange(q != null ? q.getWeeklyChange() : null)
                    .monthlyChange(q != null ? q.getMonthlyChange() : null)
                    .marketCap(q != null ? q.getMarketCap() : null)
                    .build());
        }
        // Sort by market value desc
        out.sort(Comparator.comparing(HoldingDTO::getMarketValue).reversed());
        return out;
    }

    public PortfolioSummaryDTO summary(String userSub) {
        List<HoldingDTO> hs = holdings(userSub);
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
