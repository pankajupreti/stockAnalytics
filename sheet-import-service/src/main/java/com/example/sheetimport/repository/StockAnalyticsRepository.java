package com.example.sheetimport.repository;


import com.example.sheetimport.model.StockAnalytics;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;


public interface StockAnalyticsRepository extends JpaRepository<StockAnalytics, String> {

    /**
     * Find all stocks by source (SHEET or YAHOO).
     */
    List<StockAnalytics> findBySource(String source);

    /**
     * Find stocks by ticker containing (for search).
     */
    List<StockAnalytics> findTop100ByTickerContainingIgnoreCaseOrNameContainingIgnoreCase(String ticker, String name);
}