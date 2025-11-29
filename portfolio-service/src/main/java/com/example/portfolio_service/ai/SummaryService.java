package com.example.portfolio_service.ai;

import com.example.portfolio_service.model.Position;
import com.example.portfolio_service.quotes.QuotesClient;
import com.example.portfolio_service.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

// src/main/java/.../ai/SummaryService.java
@Service
@RequiredArgsConstructor
public class SummaryService {

    private final PositionRepository positions;      // your existing repo
    private final QuotesClient quotes;              // your client with batchQuotes()

    public Facts computeFacts(String userSub, String bearerToken) {
        var pos = positions.findByUserSubOrderByIdAsc(userSub);
        var tickers = pos.stream().map(Position::getTicker).filter(Objects::nonNull).distinct().toList();

        // fetch quotes (must return ticker, cmp, dailyChange)
        var quotesList = quotes.batchQuotes(tickers, bearerToken).blockOptional().orElse(List.of());
        var byTicker = quotesList.stream().collect(Collectors.toMap(
                q -> q.getTicker().toUpperCase(), q -> q, (a,b) -> a));

        double pfCurrent = 0.0;
        List<Row> rows = new ArrayList<>();
        for (var p : pos) {
            var t = (p.getTicker()==null? "" : p.getTicker().toUpperCase());
            var q = byTicker.get(t);
            if (q == null || q.getPrice() == null) continue;
            var qty = Optional.ofNullable(p.getQuantity()).orElse(0);
            double cmp = q.getPrice().doubleValue();
            double currVal = qty * cmp;
            Double dayPct = q.getDailyChange() == null ? null : q.getDailyChange().doubleValue();
            pfCurrent += currVal;
            rows.add(new Row(t, currVal, dayPct));
        }

        double dayVal = 0.0;
        List<PortfolioSummaryDTO.Move> posMoves = new ArrayList<>();
        List<PortfolioSummaryDTO.Move> negMoves = new ArrayList<>();

        for (var r : rows) {
            if (r.dayPct() == null) continue;
            double contribVal = r.currVal() * r.dayPct() / 100.0;
            dayVal += contribVal;
            var m = new PortfolioSummaryDTO.Move(r.ticker(), safe2(r.dayPct()), safe2(contribVal));
            if (contribVal >= 0) posMoves.add(m); else negMoves.add(m);
        }

        posMoves.sort(Comparator.comparingDouble(PortfolioSummaryDTO.Move::contributionValue).reversed());
        negMoves.sort(Comparator.comparingDouble(m -> Math.abs(m.contributionValue())));
        Collections.reverse(negMoves); // largest negative first

        double dayPct = pfCurrent > 0 ? (dayVal * 100.0 / pfCurrent) : 0.0;

        return new Facts(safe2(dayPct), safe2(dayVal),
                posMoves.stream().limit(3).toList(),
                negMoves.stream().limit(3).toList());
    }

    private static double safe2(double v){ return Math.round(v * 100.0) / 100.0; }

    record Row(String ticker, double currVal, Double dayPct) {}
    public record Facts(double dayPercent, double dayValue,
                        List<PortfolioSummaryDTO.Move> leaders,
                        List<PortfolioSummaryDTO.Move> laggards) {}
}
