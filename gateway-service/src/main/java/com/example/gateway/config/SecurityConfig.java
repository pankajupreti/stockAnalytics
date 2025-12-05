package com.example.gateway.config;

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

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> {}) // use global CORS from application.properties
                .authorizeExchange(exchanges -> exchanges
                        // allow preflight requests
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // allow static SPA files
                        .pathMatchers("/", "/index.html", "/marketbreadth.html","/dashboard.html", "/portfolio.html","/style.css","/assets/images/logo-icon.png","/assets/images/google-logo.svg","/app.js","/portfolio.js", "/marketbreadth.js", "/js/**", "/images/**").permitAll()

                        // allow dashboard page but APIs will still be protected
                        .pathMatchers("/dashboard/**").permitAll()

                        // OAuth2 login & callback handled by *gateway itself*
                        .pathMatchers("/login/**", "/oauth2/**").permitAll()

                        // allow OAuth handshake endpoints proxied to oauth-service
                        .pathMatchers("/oauth-service/oauth2/**").permitAll()
                        .pathMatchers("/oauth-service/login/oauth2/**").permitAll() // Google callback
                        .pathMatchers("/oauth-service/user-token").permitAll()
                        .pathMatchers("/oauth-service/.well-known/**").permitAll()

                        // everything else requires JWT
                        .anyExchange().authenticated()
                )
                // enable OAuth2 login (Google client from properties) for app-render
               // .oauth2Login(Customizer.withDefaults())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt()) // validate JWT on APIs
                .build();
    }
}
