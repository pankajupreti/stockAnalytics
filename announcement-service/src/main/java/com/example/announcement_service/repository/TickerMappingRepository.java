package com.example.announcement_service.repository;

import com.example.announcement_service.model.TickerMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TickerMappingRepository extends JpaRepository<TickerMapping, Long> {

    Optional<TickerMapping> findByScripCode(String scripCode);

    Optional<TickerMapping> findByNseTickerIgnoreCase(String nseTicker);

    Optional<TickerMapping> findByIsin(String isin);

    List<TickerMapping> findByActiveTrue();

    @Query("SELECT tm FROM TickerMapping tm WHERE UPPER(tm.nseTicker) IN :tickers")
    List<TickerMapping> findByNseTickersIn(@Param("tickers") List<String> tickers);

    @Query("SELECT tm FROM TickerMapping tm WHERE tm.scripCode IN :scripCodes")
    List<TickerMapping> findByScripCodesIn(@Param("scripCodes") List<String> scripCodes);

    boolean existsByScripCode(String scripCode);

    long countByNseTickerIsNotNull();

    @Query("SELECT tm.nseTicker FROM TickerMapping tm WHERE tm.nseTicker IS NOT NULL")
    List<String> findAllNseTickers();
}
