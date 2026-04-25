package com.example.announcement_service.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnnouncementDTO {
    private Long id;
    private String scripCode;
    private String ticker;
    private String nseTicker;  // NSE ticker symbol for matching with portfolio (e.g., "TCS", "RELIANCE")
    private String companyName;
    private String subject;
    private String category;
    private String subCategory;
    private LocalDateTime announcementDate;
    private String broadcastDateTime;  // Original broadcast date/time from BSE
    private String pdfUrl;
    private String newsId;
    private String aiSummary;  // AI-generated summary of the announcement
    private Boolean seen;      // Whether user has seen this announcement
    private LocalDateTime seenAt;  // When user marked as seen
}
