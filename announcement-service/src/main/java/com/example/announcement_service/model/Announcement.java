package com.example.announcement_service.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "announcements", indexes = {
        @Index(name = "idx_announcements_ticker", columnList = "ticker"),
        @Index(name = "idx_announcements_nse_ticker", columnList = "nseTicker"),
        @Index(name = "idx_announcements_scrip_code", columnList = "scripCode"),
        @Index(name = "idx_announcements_date", columnList = "announcementDate"),
        @Index(name = "idx_announcements_category", columnList = "category"),
        @Index(name = "idx_announcements_user_id", columnList = "userId"),
        @Index(name = "idx_announcements_user_seen", columnList = "userId, seen")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String scripCode;

    @Column(nullable = false, length = 32)
    private String ticker;

    /**
     * NSE ticker symbol (e.g., "RELIANCE", "TCS") for matching with portfolio stocks.
     * Mapped from BSE scrip code using TickerMappingService.
     * May be null if no mapping exists.
     */
    @Column(length = 32)
    private String nseTicker;

    @Column(nullable = false, length = 256)
    private String companyName;

    @Column(nullable = false, length = 1024)
    private String subject;

    @Column(length = 128)
    private String category;

    @Column(length = 128)
    private String subCategory;

    @Column(nullable = false)
    private LocalDateTime announcementDate;

    @Column(length = 512)
    private String pdfUrl;

    @Column(length = 64, unique = true)
    private String newsId;

    @Column(length = 64)
    private String broadcastDateTime;

    @Column
    private LocalDateTime createdAt;

    /**
     * User ID (from OAuth/JWT sub claim) - for per-user seen status tracking
     */
    @Column(length = 128)
    private String userId;

    /**
     * Whether user has seen/viewed this announcement.
     * NULL or false = unseen, true = seen.
     */
    @Column
    @Builder.Default
    private Boolean seen = false;

    /**
     * When the announcement was first viewed by the user
     */
    private LocalDateTime seenAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (seen == null) {
            seen = false;
        }
    }
}
