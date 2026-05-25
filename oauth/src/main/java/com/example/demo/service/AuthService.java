package com.example.demo.service;

import com.example.demo.model.UserCredential;
import com.example.demo.model.UserToken;
import com.example.demo.repository.UserCredentialRepository;
import com.example.demo.repository.UserTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class AuthService {

    private final UserCredentialRepository credentialRepo;
    private final UserTokenRepository tokenRepo;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;

    @Value("${oauth.issuer}")
    private String issuer;

    public AuthService(UserCredentialRepository credentialRepo,
                       UserTokenRepository tokenRepo,
                       BCryptPasswordEncoder passwordEncoder,
                       JwtEncoder jwtEncoder) {
        this.credentialRepo = credentialRepo;
        this.tokenRepo = tokenRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
    }

    public AuthResult register(String email, String password, String name) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }

        // Check if email already registered via email/password
        if (credentialRepo.existsByEmail(email)) {
            throw new IllegalArgumentException("An account with this email already exists");
        }

        // Check if a Google user exists with this email
        boolean googleUserExists = tokenRepo.findAll().stream()
                .anyMatch(u -> email.equalsIgnoreCase(u.getEmail()) && !u.getSub().startsWith("email-"));
        if (googleUserExists) {
            throw new IllegalArgumentException("This email is already linked to a Google account. Please login with Google.");
        }

        String sub = "email-" + UUID.randomUUID();
        String hashedPassword = passwordEncoder.encode(password);

        // Save credential
        UserCredential credential = new UserCredential(email, hashedPassword, name, sub);
        credentialRepo.save(credential);

        // Create UserToken so rest of the system works (plans, portfolio, etc.)
        String refreshToken = UUID.randomUUID().toString();
        UserToken userToken = new UserToken(sub, email, name, refreshToken, null, null);
        userToken.setLastActivity(Instant.now());
        tokenRepo.save(userToken);

        // Generate JWT
        String jwt = generateJwt(sub, email, name);

        return new AuthResult(jwt, refreshToken, email, name);
    }

    public AuthResult login(String email, String password) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password is required");
        }

        // Find credential
        UserCredential credential = credentialRepo.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

        // Verify password
        if (!passwordEncoder.matches(password, credential.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        // Find UserToken by sub
        String sub = credential.getUserSub();
        UserToken userToken = tokenRepo.findById(sub).orElse(null);
        if (userToken == null) {
            // Shouldn't happen, but recreate if missing
            String refreshToken = UUID.randomUUID().toString();
            userToken = new UserToken(sub, email, credential.getName(), refreshToken, null, null);
        }

        // Update last activity and refresh token
        userToken.setLastActivity(Instant.now());
        String refreshToken = UUID.randomUUID().toString();
        userToken.setRefreshToken(refreshToken);
        tokenRepo.save(userToken);

        // Generate JWT
        String jwt = generateJwt(sub, email, credential.getName());

        return new AuthResult(jwt, refreshToken, email, credential.getName());
    }

    private String generateJwt(String sub, String email, String name) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject(sub)
                .claim("scope", "OIDC_USER")
                .claim("email", email != null ? email : "")
                .claim("name", name != null ? name : "")
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    public record AuthResult(String accessToken, String refreshToken, String email, String name) {}
}
