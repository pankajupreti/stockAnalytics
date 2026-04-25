package com.example.announcement_service.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BseAnnouncementResponse {

    @JsonProperty("Table")
    private List<BseAnnouncement> table;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BseAnnouncement {
        @JsonProperty("NEWSID")
        private String newsId;

        @JsonProperty("SCRIP_CD")
        private String scripCode;

        @JsonProperty("SLONGNAME")
        private String companyName;

        @JsonProperty("NEWSSUB")
        private String subject;

        @JsonProperty("NEWS_DT")
        private String newsDate;

        @JsonProperty("CATEGORYNAME")
        private String category;

        @JsonProperty("SUBCATNAME")
        private String subCategory;

        @JsonProperty("ATTACHMENTNAME")
        private String attachmentName;

        @JsonProperty("NSESSION_TIME")
        private String sessionTime;

        @JsonProperty("HEADLINE")
        private String headline;

        @JsonProperty("DT_TM")
        private String dateTime;
    }
}
