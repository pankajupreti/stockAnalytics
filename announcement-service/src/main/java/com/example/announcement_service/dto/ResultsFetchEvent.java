package com.example.announcement_service.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Event published to RabbitMQ when a financial results announcement is detected.
 * The results-service consumer will attempt to fetch data from Screener.in
 * with retry logic (every 6 hours, max 5 attempts).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResultsFetchEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** NSE ticker symbol (e.g., "NETWEB", "TCS") */
    private String ticker;

    /** Company name from BSE announcement */
    private String companyName;

    /** Announcement ID for reference */
    private Long announcementId;

    /** BSE News ID */
    private String newsId;

    /** When the announcement was made */
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime announcementTime;

    /** When this event was created */
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime eventTime;

    /** Current attempt number (starts at 1) */
    private int attemptNumber;

    /** Max retry attempts */
    private int maxAttempts;

    /** Subject of the announcement */
    private String subject;

    /** Expected quarter from announcement (e.g., "Q3 FY2026") */
    private String expectedQuarter;
}
