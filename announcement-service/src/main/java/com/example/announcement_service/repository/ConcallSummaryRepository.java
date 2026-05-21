package com.example.announcement_service.repository;

import com.example.announcement_service.model.ConcallSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConcallSummaryRepository extends JpaRepository<ConcallSummary, Long> {

    Optional<ConcallSummary> findByAnnouncementId(Long announcementId);

    @Query("SELECT c.announcementId FROM ConcallSummary c WHERE c.announcementId IN :announcementIds")
    List<Long> findExistingAnnouncementIds(@Param("announcementIds") List<Long> announcementIds);

    List<ConcallSummary> findByTickerIgnoreCase(String ticker);
}
