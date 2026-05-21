package com.example.portfolio_service.repository;

import com.example.portfolio_service.model.Position;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PositionRepository extends JpaRepository<Position, Long> {
    List<Position> findByUserSubOrderByIdAsc(String userSub);

    // fetch single position by id + owner
    Optional<Position> findByIdAndUserSub(Long id, String userSub);

    // Find position by user and ticker (for add/sell operations)
    // Uses LIMIT 1 to avoid NonUniqueResultException if duplicates exist
    @Query("SELECT p FROM Position p WHERE p.userSub = :userSub AND p.ticker = :ticker ORDER BY p.id ASC LIMIT 1")
    Optional<Position> findByUserSubAndTicker(@org.springframework.data.repository.query.Param("userSub") String userSub, @org.springframework.data.repository.query.Param("ticker") String ticker);

    // Get all distinct user IDs who have positions (for scheduled snapshot capture)
    @Query("SELECT DISTINCT p.userSub FROM Position p")
    List<String> findDistinctUserSubs();
}
