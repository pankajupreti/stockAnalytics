package com.example.announcement_service.service;

import com.example.announcement_service.model.Announcement;
import com.example.announcement_service.repository.AnnouncementRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Separate service for announcement persistence operations.
 * Uses programmatic transaction management to completely isolate each save
 * and prevent session corruption from affecting other operations.
 */
@Service
@Slf4j
public class AnnouncementPersistenceService {

    private final AnnouncementRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final PricePrefetchService pricePrefetchService;

    @PersistenceContext
    private EntityManager entityManager;

    public AnnouncementPersistenceService(AnnouncementRepository repository,
                                          PlatformTransactionManager transactionManager,
                                          @Lazy PricePrefetchService pricePrefetchService) {
        this.repository = repository;
        this.pricePrefetchService = pricePrefetchService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Save announcement with duplicate handling.
     * Uses programmatic transaction to completely isolate each save operation.
     * Returns the saved entity, or null if duplicate (already exists).
     */
    public Announcement saveAnnouncementSafely(Announcement announcement) {
        if (announcement.getNewsId() == null) {
            return null;
        }

        // First check if it exists (optimistic check outside transaction)
        if (repository.existsByNewsId(announcement.getNewsId())) {
            log.debug("Announcement {} already exists, skipping", announcement.getNewsId());
            return null;
        }

        try {
            Announcement saved = transactionTemplate.execute(status -> {
                try {
                    // Clear any existing state to ensure clean session
                    entityManager.clear();
                    Announcement result = repository.saveAndFlush(announcement);
                    return result;
                } catch (Exception e) {
                    // Mark transaction for rollback but don't throw
                    status.setRollbackOnly();
                    log.debug("Failed to save announcement {} (likely duplicate): {}",
                            announcement.getNewsId(), e.getMessage());
                    return null;
                }
            });

            // Trigger async price pre-fetch for financial result announcements
            if (saved != null && pricePrefetchService != null) {
                pricePrefetchService.prefetchPriceForAnnouncement(saved);
            }

            return saved;
        } catch (Exception e) {
            // Catch any transaction-level exceptions
            log.debug("Transaction failed for announcement {}: {}",
                    announcement.getNewsId(), e.getMessage());
            return null;
        }
    }
}
