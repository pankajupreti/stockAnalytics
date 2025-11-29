package com.example.demo.controller;

import com.example.demo.service.TokenManagerService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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

    // ✅ Stateless refresh token handling
    @PostMapping("/token/refresh")
    public ResponseEntity<Map<String, String>> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        String newAccessToken = tokenManagerService.getValidTokenByRefreshToken(refreshToken);
        return ResponseEntity.ok(Map.of("access_token", newAccessToken));
    }
}
