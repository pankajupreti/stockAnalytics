package com.example.demo.controller;

import com.example.demo.service.AuthService;
import com.example.demo.service.AuthService.AuthResult;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @Value("${frontend.base-url}")
    private String frontendBaseUrl;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(
            @RequestBody Map<String, String> body,
            HttpServletResponse response) {
        try {
            String email = body.get("email");
            String password = body.get("password");
            String name = body.get("name");

            AuthResult result = authService.register(email, password, name);
            setAuthCookies(response, result);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "email", result.email(),
                    "name", result.name() != null ? result.name() : ""
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Registration failed"));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(
            @RequestBody Map<String, String> body,
            HttpServletResponse response) {
        try {
            String email = body.get("email");
            String password = body.get("password");

            AuthResult result = authService.login(email, password);
            setAuthCookies(response, result);

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "email", result.email(),
                    "name", result.name() != null ? result.name() : ""
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Login failed"));
        }
    }

    private void setAuthCookies(HttpServletResponse response, AuthResult result) {
        boolean isSecure = frontendBaseUrl.startsWith("https");
        long accessTokenMaxAge = 3600; // 1 hour
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);

        // access_token — HttpOnly, Path=/
        Cookie accessCookie = new Cookie("access_token", result.accessToken());
        accessCookie.setHttpOnly(true);
        accessCookie.setSecure(isSecure);
        accessCookie.setPath("/");
        accessCookie.setMaxAge((int) accessTokenMaxAge);
        response.addCookie(accessCookie);

        // refresh_token — HttpOnly, narrow path
        Cookie refreshCookie = new Cookie("refresh_token", result.refreshToken());
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(isSecure);
        refreshCookie.setPath("/oauth-service/token");
        refreshCookie.setMaxAge(2592000); // 30 days
        response.addCookie(refreshCookie);

        // token_meta — readable by JS
        String metaValue = "exp=" + expiresAt.getEpochSecond()
                + "&email=" + URLEncoder.encode(result.email() != null ? result.email() : "", StandardCharsets.UTF_8)
                + "&name=" + URLEncoder.encode(result.name() != null ? result.name() : "", StandardCharsets.UTF_8);
        Cookie metaCookie = new Cookie("token_meta", metaValue);
        metaCookie.setHttpOnly(false);
        metaCookie.setSecure(isSecure);
        metaCookie.setPath("/");
        metaCookie.setMaxAge((int) accessTokenMaxAge);
        response.addCookie(metaCookie);
    }
}
