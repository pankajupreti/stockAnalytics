package com.example.announcement_service.dto;

import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortfolioAnnouncementDTO {
    private String ticker;
    private String companyName;
    private int announcementCount;
    private List<AnnouncementDTO> recentAnnouncements;
}
