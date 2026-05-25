package com.example.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        log.info("=== Gateway Security Config: /oauth-service/token/refresh is PERMITTED (no JWT required) ===");
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> {}) // use global CORS from application.properties
                .authorizeExchange(exchanges -> exchanges
                        // allow preflight requests
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // allow static SPA files
                        .pathMatchers("/", "/index.html", "/marketbreadth.html", "/dashboard.html", "/portfolio.html", "/portfolio-analytics.html", "/pnl-report.html", "/announcements.html", "/alerts.html", "/52w-breakouts.html", "/results-analysis.html", "/good-results.html", "/pead-scanner.html", "/health.html", "/admin.html", "/style.css", "/styles.css", "/assets/images/logo-icon.png", "/assets/images/google-logo.svg", "/app.js", "/portfolio.js", "/marketbreadth.js", "/token-utils.js", "/js/**", "/images/**").permitAll()

                        // allow system health API (server-side health checks)
                        .pathMatchers("/api/system/**").permitAll()

                        // allow announcement service test endpoints (public)
                        .pathMatchers("/announcement-service/api/test/**").permitAll()

                        // allow PEAD scanner endpoints (public)
                        .pathMatchers("/announcement-service/api/pead/**").permitAll()

                        // allow alert service test endpoints (public)
                        .pathMatchers("/alert-service/api/test/**").permitAll()

                        // allow dashboard page but APIs will still be protected
                        .pathMatchers("/dashboard/**").permitAll()

                        // OAuth2 login & callback handled by *gateway itself*
                        .pathMatchers("/login/**", "/oauth2/**").permitAll()

                        // allow OAuth handshake endpoints proxied to oauth-service
                        .pathMatchers("/oauth-service/oauth2/**").permitAll()
                        .pathMatchers("/oauth-service/login/oauth2/**").permitAll() // Google callback
                        .pathMatchers("/oauth-service/user-token").permitAll()
                        .pathMatchers("/oauth-service/.well-known/**").permitAll()
                        // Email/password auth endpoints (public, no JWT)
                        .pathMatchers("/oauth-service/api/auth/**").permitAll()
                        // Token refresh/revoke/info must be public (called when JWT expired or for session check)
                        .pathMatchers("/oauth-service/token/refresh").permitAll()
                        .pathMatchers("/oauth-service/token/revoke").permitAll()
                        .pathMatchers("/oauth-service/token/info").permitAll()

                        // allow actuator endpoints for Prometheus scraping
                        .pathMatchers("/actuator/**").permitAll()

                        // everything else requires JWT
                        .anyExchange().authenticated()
                )
                // enable OAuth2 login (Google client from properties) for app-render
               // .oauth2Login(Customizer.withDefaults())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt()) // validate JWT on APIs
                .build();
    }
}
