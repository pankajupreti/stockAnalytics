package com.example.demo.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import java.time.Instant;

@Entity



public class UserToken {

    @Id
    private String sub;

    private String email;

    @Lob
    private String refreshToken;

    @Lob
    private String accessToken;

    private Instant expiresAt;


    public UserToken(){

    }

    public UserToken(String sub, String email, String refreshToken, String accessToken, Instant expiresAt) {
        this.sub = sub;
        this.email = email;
        this.refreshToken = refreshToken;
        this.accessToken = accessToken;
        this.expiresAt = expiresAt;
    }


    // --- Getters ---
    public String getSub() { return sub; }

    public String getEmail() { return email; }

    public String getRefreshToken() { return refreshToken; }

    public String getAccessToken() { return accessToken; }

    public Instant getExpiresAt() { return expiresAt; }

    // --- Setters ---
    public void setSub(String sub) { this.sub = sub; }

    public void setEmail(String email) { this.email = email; }

    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
