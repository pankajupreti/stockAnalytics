package com.example.demo.service;

import com.example.demo.model.UserToken;
import com.example.demo.repository.UserTokenRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Service
public class TokenManagerService {

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret}")
    private String clientSecret;

    @Value("${oauth.issuer}")
    private String issuer;

    @Autowired
    private UserTokenRepository repo;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final RestTemplate restTemplate = new RestTemplate();


    /**
     * Validates the refresh token and returns a new OAuth JWT (not Google's token).
     * This ensures the frontend always gets a fresh JWT signed by our server.
     *
     * OPTIMIZED: We no longer call Google on every refresh. As long as the user
     * exists in our database with a valid refresh token, we issue a new JWT.
     * Google validation only happens during initial login.
     */
    public String getValidTokenByRefreshToken(String refreshToken) {
        System.out.println("🔄 Token refresh requested at " + Instant.now());

        if (refreshToken == null || refreshToken.isEmpty()) {
            System.out.println("❌ Refresh token is null or empty");
            throw new RuntimeException("Refresh token is required");
        }

        // Find user by refresh token
        UserToken user = repo.findAll().stream()
                .filter(u -> refreshToken.equals(u.getRefreshToken()))
                .findFirst()
                .orElse(null);

        if (user == null) {
            System.out.println("❌ No user found with this refresh token");
            throw new RuntimeException("Invalid refresh token - user not found");
        }

        System.out.println("✅ Found user: " + user.getEmail() + ", stayLoggedIn: " + user.getStayLoggedIn());

        // Check if user has been inactive for too long (30 days for stayLoggedIn, 7 days otherwise)
        Instant lastActivity = user.getLastActivity();
        if (lastActivity != null) {
            long maxInactiveDays = Boolean.TRUE.equals(user.getStayLoggedIn()) ? 30 : 7;
            Instant cutoff = Instant.now().minus(maxInactiveDays, ChronoUnit.DAYS);
            if (lastActivity.isBefore(cutoff)) {
                System.out.println("❌ User inactive for more than " + maxInactiveDays + " days, requiring re-login");
                // Clear tokens to force re-login
                user.setRefreshToken(null);
                user.setAccessToken(null);
                user.setExpiresAt(null);
                repo.save(user);
                throw new RuntimeException("Session expired due to inactivity. Please re-login.");
            }
        }

        // Update last activity
        user.setLastActivity(Instant.now());
        repo.save(user);

        // Generate and return a new OAuth JWT - NO Google validation needed!
        String newJwt = generateOAuthJwt(user);
        System.out.println("✅ Generated new JWT for user: " + user.getEmail());
        return newJwt;
    }

    /**
     * Generates a new OAuth JWT for the user.
     */
    private String generateOAuthJwt(UserToken user) {
        Instant now = Instant.now();

        // Check if user has "stay logged in" enabled - use longer expiry
        long expiryHours = Boolean.TRUE.equals(user.getStayLoggedIn()) ? 24 : 1;

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(expiryHours, ChronoUnit.HOURS))
                .subject(user.getSub())
                .claim("email", user.getEmail())
                .claim("name", user.getName())
                .claim("scope", "OIDC_USER")
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /**
     * Validates Google refresh token and updates stored Google tokens if needed.
     */
    private void validateAndRefreshGoogleToken(UserToken user) {
        // If Google token is still valid, no need to refresh
        if (user.getAccessToken() != null && user.getExpiresAt() != null &&
                Instant.now().isBefore(user.getExpiresAt())) {
            return;
        }

        if (user.getRefreshToken() == null) {
            throw new RuntimeException("No refresh token available for user: " + user.getSub());
        }

        // Refresh with Google
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", user.getRefreshToken());
        form.add("grant_type", "refresh_token");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);

        try {
            System.out.println("🔄 Calling Google token refresh for user: " + user.getEmail());
            ResponseEntity<Map> response = restTemplate.exchange(
                    "https://oauth2.googleapis.com/token",
                    HttpMethod.POST,
                    request,
                    Map.class
            );

            Map<String, Object> body = response.getBody();
            System.out.println("✅ Google token refresh response: " + response.getStatusCode());

            if (body != null && body.containsKey("id_token")) {
                String newIdToken = (String) body.get("id_token");
                int expiresIn = ((Number) body.get("expires_in")).intValue();
                Instant newExpiry = Instant.now().plusSeconds(expiresIn);

                user.setAccessToken(newIdToken);
                user.setExpiresAt(newExpiry);
                repo.save(user);
                System.out.println("✅ Updated Google token, expires in " + expiresIn + "s");
            } else if (body != null && body.containsKey("access_token")) {
                // Google may return access_token instead of id_token for some flows
                String newAccessToken = (String) body.get("access_token");
                int expiresIn = ((Number) body.get("expires_in")).intValue();
                Instant newExpiry = Instant.now().plusSeconds(expiresIn);

                user.setAccessToken(newAccessToken);
                user.setExpiresAt(newExpiry);
                repo.save(user);
                System.out.println("✅ Updated Google access_token, expires in " + expiresIn + "s");
            }
        } catch (HttpClientErrorException e) {
            System.out.println("❌ Google token refresh failed: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
            // If Google rejects the refresh token, it's likely expired or revoked
            // Clear the stored tokens to force re-login
            user.setRefreshToken(null);
            user.setAccessToken(null);
            user.setExpiresAt(null);
            repo.save(user);
            throw new RuntimeException("Google refresh token expired or revoked. Please re-login.", e);
        }
    }

    /**
     * Revokes the refresh token for a user (logout).
     */
    public void revokeToken(String refreshToken) {
        UserToken user = repo.findAll().stream()
                .filter(u -> refreshToken.equals(u.getRefreshToken()))
                .findFirst()
                .orElse(null);

        if (user != null) {
            // Revoke with Google
            try {
                restTemplate.postForEntity(
                        "https://oauth2.googleapis.com/revoke?token=" + refreshToken,
                        null,
                        String.class
                );
            } catch (Exception e) {
                // Log but don't fail - token might already be revoked
                System.out.println("Google revoke failed (may already be revoked): " + e.getMessage());
            }

            // Clear stored tokens
            user.setRefreshToken(null);
            user.setAccessToken(null);
            user.setExpiresAt(null);
            repo.save(user);
        }
    }

    /**
     * Updates the "stay logged in" preference for a user.
     */
    public void updateStayLoggedIn(String sub, boolean stayLoggedIn) {
        UserToken user = repo.findById(sub)
                .orElseThrow(() -> new RuntimeException("User not found: " + sub));

        user.setStayLoggedIn(stayLoggedIn);
        repo.save(user);
    }

    /**
     * Gets user info by subject.
     */
    public UserToken getUserBySub(String sub) {
        return repo.findById(sub).orElse(null);
    }
}
