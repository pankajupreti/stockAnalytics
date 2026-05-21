package com.example.announcement_service.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "concall_summaries", indexes = {
        @Index(name = "idx_concall_ticker", columnList = "ticker"),
        @Index(name = "idx_concall_announcement_id", columnList = "announcementId")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConcallSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long announcementId;

    @Column(nullable = false, length = 50)
    private String ticker;

    @Column(length = 20)
    private String quarter;

    @Column(columnDefinition = "TEXT")
    private String summaryText;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private SummaryStatus status;

    private Integer pdfPageCount;

    private Integer textLength;

    private LocalDateTime generatedAt;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public enum SummaryStatus {
        SUCCESS,
        PDF_ERROR,
        AI_ERROR,
        AI_DISABLED,
        PROCESSING
    }
}
