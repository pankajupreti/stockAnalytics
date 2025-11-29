
package com.example.reporting.repository;

import com.example.reporting.model.StockAnalytics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface StockAnalyticsRepository extends JpaRepository<StockAnalytics, String> {
    // Use DB to filter out micro caps if caller passes minMarketCap
    List<StockAnalytics> findByMarketCapGreaterThanEqual(Double minMarketCap);


    List<StockAnalytics> findByTickerIn(Collection<String> tickers);




    Optional<StockAnalytics> findFirstByTickerIgnoreCase(String ticker);

    // used for search/resolve fallbacks (limit in method name keeps queries lean)
    List<StockAnalytics> findTop100ByTickerContainingIgnoreCaseOrNameContainingIgnoreCase(String tickerLike,
                                                                                          String nameLike);
}
