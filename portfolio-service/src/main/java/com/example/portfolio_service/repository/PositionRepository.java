package com.example.portfolio_service.repository;

import com.example.portfolio_service.model.Position;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PositionRepository extends JpaRepository<Position, Long> {
    List<Position> findByUserSubOrderByIdAsc(String userSub);
/*    List<Position> findAllByUserIdOrderByBuyDateAscIdAsc(String userId);
    Optional<Position> findByIdAndUserId(Long id, String userId);*/
// fetch single position by id + owner
Optional<Position> findByIdAndUserSub(Long id, String userSub);
    // fetch all holdings for a specific user

}
