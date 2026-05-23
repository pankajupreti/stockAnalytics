package com.example.demo.controller;

import com.example.demo.service.TokenManagerService;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import com.example.demo.service.TokenService;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@RestController
public class TokenController {

    @Autowired
    private TokenManagerService tokenManagerService;

    private final TokenService tokenService;

    @Value("${frontend.base-url}")
    private String frontendBaseUrl;

    public TokenController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    // Stateless: no HttpSession, no JSESSIONID
    @GetMapping("/user-token")
    public Map<String, String> token(Authentication authentication) {
        System.out.println("INSIDE TOKEN CONTROLLER**********************");
        if (authentication == null || !authentication.isAuthenticated()) {
            return Map.of("error", "Not authenticated");
        }
        // generate signed JWT
        String jwt = tokenService.generateToken(authentication);
        return Map.of("access_token", jwt);
    }

    // Stateless refresh token handling - returns new OAuth JWT
    // Reads refresh_token from cookie (with POST body fallback for transition)
    @PostMapping("/token/refresh")
    public ResponseEntity<Map<String, String>> refresh(
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        System.out.println("=== TOKEN REFRESH REQUEST ===");
        try {
            // Try cookie first, fall back to POST body
            String refreshToken = getCookieValue(request, "refresh_token");
            if (refreshToken == null && body != null) {
                refreshToken = body.get("refreshToken");
            }

            if (refreshToken == null || refreshToken.isEmpty()) {
                System.out.println("Missing refreshToken in cookie and body");
                return ResponseEntity.badRequest().body(Map.of("error", "Missing refreshToken"));
            }
            System.out.println("Refresh token received (from " + (getCookieValue(request, "refresh_token") != null ? "cookie" : "body") + ")");

            String newAccessToken = tokenManagerService.getValidTokenByRefreshToken(refreshToken);
            System.out.println("Token refresh SUCCESS");

            boolean isSecure = frontendBaseUrl.startsWith("https");

            // Set new access_token cookie
            Cookie accessCookie = new Cookie("access_token", newAccessToken);
            accessCookie.setHttpOnly(true);
            accessCookie.setSecure(isSecure);
            accessCookie.setPath("/");
            accessCookie.setMaxAge(3600);
            response.addCookie(accessCookie);

            // Set new token_meta cookie with updated expiry
            Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
            // Parse email/name from the new token for meta cookie
            String email = "";
            String name = "";
            try {
                String[] parts = newAccessToken.split("\\.");
                if (parts.length == 3) {
                    String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                    var claims = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
                    if (claims.has("email")) email = claims.get("email").asText();
                    if (claims.has("name")) name = claims.get("name").asText();
                }
            } catch (Exception e) {
                System.out.println("Could not parse claims from new token: " + e.getMessage());
            }

            String metaValue = "exp=" + expiresAt.getEpochSecond()
                    + "&email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                    + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
            Cookie metaCookie = new Cookie("token_meta", metaValue);
            metaCookie.setHttpOnly(false);
            metaCookie.setSecure(isSecure);
            metaCookie.setPath("/");
            metaCookie.setMaxAge(3600);
            response.addCookie(metaCookie);

            // Also return JSON for backwards compat
            return ResponseEntity.ok(Map.of("access_token", newAccessToken));
        } catch (Exception e) {
            System.out.println("Token refresh FAILED: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    // Token revocation (logout) — clears cookies
    @PostMapping("/token/revoke")
    public ResponseEntity<Map<String, String>> revoke(
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            // Try cookie first, fall back to POST body
            String refreshToken = getCookieValue(request, "refresh_token");
            if (refreshToken == null && body != null) {
                refreshToken = body.get("refreshToken");
            }

            if (refreshToken != null && !refreshToken.isEmpty()) {
                tokenManagerService.revokeToken(refreshToken);
            }

            boolean isSecure = frontendBaseUrl.startsWith("https");

            // Clear all auth cookies
            clearCookie(response, "access_token", "/", isSecure);
            clearCookie(response, "refresh_token", "/oauth-service/token", isSecure);
            clearCookie(response, "token_meta", "/", isSecure);

            return ResponseEntity.ok(Map.of("status", "revoked"));
        } catch (Exception e) {
            // Still clear cookies even if revoke fails
            boolean isSecure = frontendBaseUrl.startsWith("https");
            clearCookie(response, "access_token", "/", isSecure);
            clearCookie(response, "refresh_token", "/oauth-service/token", isSecure);
            clearCookie(response, "token_meta", "/", isSecure);
            return ResponseEntity.ok(Map.of("status", "revoked", "note", e.getMessage()));
        }
    }

    // Lightweight endpoint for JS to check session validity and get token metadata
    @GetMapping("/token/info")
    public ResponseEntity<Map<String, Object>> tokenInfo(HttpServletRequest request) {
        String accessToken = getCookieValue(request, "access_token");
        if (accessToken == null || accessToken.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("authenticated", false));
        }

        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length != 3) {
                return ResponseEntity.status(401).body(Map.of("authenticated", false));
            }
            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            var claims = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);

            long exp = claims.has("exp") ? claims.get("exp").asLong() : 0;
            long now = Instant.now().getEpochSecond();

            if (exp <= now) {
                return ResponseEntity.status(401).body(Map.of("authenticated", false, "reason", "expired"));
            }

            return ResponseEntity.ok(Map.of(
                    "authenticated", true,
                    "expiresIn", exp - now,
                    "email", claims.has("email") ? claims.get("email").asText() : "",
                    "name", claims.has("name") ? claims.get("name").asText() : "",
                    "sub", claims.has("sub") ? claims.get("sub").asText() : ""
            ));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("authenticated", false));
        }
    }

    // Update "stay logged in" preference
    @PostMapping("/user/stay-logged-in")
    public ResponseEntity<Map<String, String>> updateStayLoggedIn(
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated()) {
                return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
            }

            // Get sub from JWT
            String sub;
            if (authentication.getPrincipal() instanceof Jwt jwt) {
                sub = jwt.getSubject();
            } else {
                sub = authentication.getName();
            }

            Boolean stayLoggedIn = (Boolean) body.getOrDefault("stayLoggedIn", false);
            tokenManagerService.updateStayLoggedIn(sub, stayLoggedIn);

            return ResponseEntity.ok(Map.of("status", "updated", "stayLoggedIn", stayLoggedIn.toString()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void clearCookie(HttpServletResponse response, String name, String path, boolean secure) {
        Cookie cookie = new Cookie(name, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath(path);
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }
}
