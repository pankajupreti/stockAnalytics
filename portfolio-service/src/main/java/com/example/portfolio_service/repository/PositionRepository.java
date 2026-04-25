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
    Optional<Position> findByUserSubAndTicker(String userSub, String ticker);

    // Get all distinct user IDs who have positions (for scheduled snapshot capture)
    @Query("SELECT DISTINCT p.userSub FROM Position p")
    List<String> findDistinctUserSubs();
}
