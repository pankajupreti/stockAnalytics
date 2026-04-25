package com.example.alert_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enable async processing for notification sending.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
