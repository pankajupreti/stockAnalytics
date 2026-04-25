package com.example.demo.controller;

import com.example.demo.service.TokenManagerService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import com.example.demo.service.TokenService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class TokenController {

    @Autowired
    private TokenManagerService tokenManagerService;

    private final TokenService tokenService;

    public TokenController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    // ✅ Stateless: no HttpSession, no JSESSIONID
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

    // ✅ Stateless refresh token handling - returns new OAuth JWT
    @PostMapping("/token/refresh")
    public ResponseEntity<Map<String, String>> refresh(@RequestBody Map<String, String> body) {
        System.out.println("=== TOKEN REFRESH REQUEST ===");
        try {
            String refreshToken = body.get("refreshToken");
            if (refreshToken == null || refreshToken.isEmpty()) {
                System.out.println("❌ Missing refreshToken in request body");
                return ResponseEntity.badRequest().body(Map.of("error", "Missing refreshToken"));
            }
            System.out.println("📝 Refresh token received: " + refreshToken.substring(0, Math.min(20, refreshToken.length())) + "...");
            String newAccessToken = tokenManagerService.getValidTokenByRefreshToken(refreshToken);
            System.out.println("✅ Token refresh SUCCESS - returning new access_token");
            return ResponseEntity.ok(Map.of("access_token", newAccessToken));
        } catch (Exception e) {
            System.out.println("❌ Token refresh FAILED: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    // ✅ Token revocation (logout)
    @PostMapping("/token/revoke")
    public ResponseEntity<Map<String, String>> revoke(@RequestBody Map<String, String> body) {
        try {
            String refreshToken = body.get("refreshToken");
            if (refreshToken != null && !refreshToken.isEmpty()) {
                tokenManagerService.revokeToken(refreshToken);
            }
            return ResponseEntity.ok(Map.of("status", "revoked"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("status", "revoked", "note", e.getMessage()));
        }
    }

    // ✅ Update "stay logged in" preference
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
}
