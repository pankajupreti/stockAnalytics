package com.example.demo.service;

import com.example.demo.model.UserToken;
import com.example.demo.repository.UserTokenRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.util.Map;

@Service
public class TokenManagerService {

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret}")
    private String clientSecret;

    @Autowired
    private UserTokenRepository repo;

    private final RestTemplate restTemplate = new RestTemplate();


    public String getValidTokenByRefreshToken(String refreshToken) {
        UserToken user = repo.findAll().stream()
                .filter(u -> refreshToken.equals(u.getRefreshToken()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Invalid refresh token"));

        return getValidToken(user.getSub());
    }

    public String getValidToken(String sub) {
        UserToken user = repo.findById(sub)
                .orElseThrow(() -> new RuntimeException("User not found: " + sub));

        // Check if current ID token is still valid
        if (user.getAccessToken() != null && user.getExpiresAt() != null &&
                Instant.now().isBefore(user.getExpiresAt())) {
            return user.getAccessToken();
        }

        if (user.getRefreshToken() == null) {
            throw new RuntimeException("No refresh token available for user: " + sub);
        }

        // Prepare request to Google's token endpoint
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", user.getRefreshToken());
        form.add("grant_type", "refresh_token");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    "https://oauth2.googleapis.com/token",
                    HttpMethod.POST,
                    request,
                    Map.class
            );

            Map<String, Object> body = response.getBody();

            if (body == null || !body.containsKey("id_token")) {
                throw new RuntimeException("Failed to refresh ID token: missing response body");
            }

            String newIdToken = (String) body.get("id_token");
            int expiresIn = ((Number) body.get("expires_in")).intValue();
            Instant newExpiry = Instant.now().plusSeconds(expiresIn);

            user.setAccessToken(newIdToken);
            user.setExpiresAt(newExpiry);
            repo.save(user);

            return newIdToken;

        } catch (HttpClientErrorException e) {
            throw new RuntimeException("Token refresh failed: " + e.getMessage(), e);
        }
    }
}
