package com.example.reporting.service;


import com.example.reporting.model.MarketBreadthResponse;
import com.example.reporting.model.StockAnalytics;
import com.example.reporting.repository.StockAnalyticsRepository;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class MarketBreadthService {

    private final StockAnalyticsRepository stockRepo;

    public MarketBreadthService(StockAnalyticsRepository stockRepo) {
        this.stockRepo = stockRepo;
    }

    public MarketBreadthResponse compute(Double minMarketCap,
                                         double[] thresholds) {

        // 1) Load universe (DB-side filter if possible)
        List<StockAnalytics> universe = (minMarketCap == null)
                ? stockRepo.findAll()
                : stockRepo.findByMarketCapGreaterThanEqual(minMarketCap);

        MarketBreadthResponse resp = new MarketBreadthResponse();
        resp.total = universe.size();

        // 2) Pull null-safe daily_change as Double list
        // daily_change column is float4 -> maps to Float in JPA, so null-safe coerce to Double
        List<Double> changes = universe.stream()
                .map(s -> s.getDailyChange() != null ? s.getDailyChange() : 0.0)
                .collect(Collectors.toList());   // use .toList() if you're on Java 16+


        // 3) Headline breadth
        resp.green = (int) changes.stream().filter(x -> x >= 0.0).count();
        resp.red   = resp.total - resp.green;
        resp.greenPct = resp.total == 0 ? 0 : round2((resp.green * 100.0) / resp.total);
        resp.greenRedRatio = round2(resp.red == 0 ? resp.green : (resp.green * 1.0 / resp.red));

        // 4) Threshold counts
        double t1 = thresholds.length > 0 ? thresholds[0] : 3.0;
        double t2 = thresholds.length > 1 ? thresholds[1] : 5.0;
        double t3 = thresholds.length > 2 ? thresholds[2] : 8.0;

        resp.above3 = (int) changes.stream().filter(x -> x >=  t1).count();
        resp.above5 = (int) changes.stream().filter(x -> x >=  t2).count();
        resp.above8 = (int) changes.stream().filter(x -> x >=  t3).count();
        resp.below3 = (int) changes.stream().filter(x -> x <= -t1).count();
        resp.below5 = (int) changes.stream().filter(x -> x <= -t2).count();
        resp.below8 = (int) changes.stream().filter(x -> x <= -t3).count();

        return resp;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}


