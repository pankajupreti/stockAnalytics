package com.example.announcement_service.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {

    /**
     * Extract the user's subject (unique ID) from the authentication
     */
    public String sub(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new IllegalStateException("No authentication found");
        }
        if (auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return auth.getName();
    }

    /**
     * Alias for sub() - get user ID from authentication
     */
    public String userId(Authentication auth) {
        return sub(auth);
    }

    /**
     * Extract the bearer token from authentication for forwarding to other services
     */
    public String token(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new IllegalStateException("No authentication found");
        }
        if (auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getTokenValue();
        }
        throw new IllegalStateException("Cannot extract token from authentication");
    }
}
