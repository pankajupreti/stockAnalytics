package com.example.demo.service;

import com.example.demo.model.UserToken;
import com.example.demo.repository.UserTokenRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class UserTokenService {

    @Autowired
    private UserTokenRepository repo;

    public void saveOrUpdateToken(OidcUser user, OAuth2AuthorizedClient client) {
        String newRefreshToken = client.getRefreshToken() != null
                ? client.getRefreshToken().getTokenValue() : null;

        String idToken = user.getIdToken().getTokenValue();
        Instant expiresAt = user.getIdToken().getExpiresAt();
        String name = user.getAttribute("name");
        String sub = user.getSubject();

        // Check if user already exists - preserve their refresh token if new one not provided
        UserToken existingToken = repo.findById(sub).orElse(null);

        UserToken token;
        if (existingToken != null) {
            // Update existing user - preserve refresh token if Google didn't send a new one
            existingToken.setEmail(user.getEmail());
            existingToken.setName(name);
            existingToken.setAccessToken(idToken);
            existingToken.setExpiresAt(expiresAt);
            existingToken.setLastActivity(Instant.now());

            // Only update refresh token if Google provided a new one
            if (newRefreshToken != null) {
                existingToken.setRefreshToken(newRefreshToken);
                System.out.println("✅ New refresh token received from Google for user: " + sub);
            } else {
                System.out.println("⚠️ No refresh token from Google, preserving existing for user: " + sub);
            }
            token = existingToken;
        } else {
            // New user - create fresh record
            token = new UserToken(
                    sub,
                    user.getEmail(),
                    name,
                    newRefreshToken,
                    idToken,
                    expiresAt
            );
            System.out.println("🆕 New user created: " + sub + ", refresh token: " + (newRefreshToken != null ? "present" : "missing"));
        }

        repo.save(token);
    }

    public String getRefreshTokenBySub(String sub) {
        return repo.findById(sub)
                .map(UserToken::getRefreshToken)
                .orElse(null);
    }

    public UserToken findBySub(String sub) {
        return repo.findById(sub).orElse(null);
    }
}

