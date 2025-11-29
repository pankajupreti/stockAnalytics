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
        String refreshToken = client.getRefreshToken() != null
                ? client.getRefreshToken().getTokenValue() : null;

        String idToken = user.getIdToken().getTokenValue();
        Instant expiresAt = user.getIdToken().getExpiresAt();

        UserToken token = new UserToken(
                user.getSubject(),
                user.getEmail(),
                refreshToken,
                idToken,
                expiresAt
        );

        repo.save(token);
    }
}

