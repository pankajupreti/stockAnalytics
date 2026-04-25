package com.example.portfolio_service.repository;

import com.example.portfolio_service.model.PortfolioSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PortfolioSnapshotRepository extends JpaRepository<PortfolioSnapshot, Long> {

    /**
     * Get all snapshots for a user ordered by date ascending.
     */
    List<PortfolioSnapshot> findByUserSubOrderBySnapshotDateAsc(String userSub);

    /**
     * Get snapshots for a user within a date range.
     */
    List<PortfolioSnapshot> findByUserSubAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            String userSub, LocalDate startDate, LocalDate endDate);

    /**
     * Get the most recent snapshot for a user.
     */
    Optional<PortfolioSnapshot> findFirstByUserSubOrderBySnapshotDateDesc(String userSub);

    /**
     * Get the oldest (first) snapshot for a user.
     */
    Optional<PortfolioSnapshot> findFirstByUserSubOrderBySnapshotDateAsc(String userSub);

    /**
     * Check if snapshot exists for a user on a specific date.
     */
    boolean existsByUserSubAndSnapshotDate(String userSub, LocalDate snapshotDate);

    /**
     * Get snapshot for a specific user and date.
     */
    Optional<PortfolioSnapshot> findByUserSubAndSnapshotDate(String userSub, LocalDate snapshotDate);

    /**
     * Count snapshots for a user.
     */
    long countByUserSub(String userSub);
}
