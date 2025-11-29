package com.example.sheetimport.repository;


import com.example.sheetimport.model.StockAnalytics;
import org.springframework.data.jpa.repository.JpaRepository;


public interface StockAnalyticsRepository extends JpaRepository<StockAnalytics, String> {
}