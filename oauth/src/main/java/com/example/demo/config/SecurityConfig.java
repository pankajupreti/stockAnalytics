package com.example.demo.config;

import com.example.demo.service.UserTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity

public class SecurityConfig {

    private final JwtEncoder jwtEncoder;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final UserTokenService userTokenService;

    @Value("${oauth.issuer}")
    private String issuer;

    @Value("${frontend.base-url}")
    private String frontendBaseUrl;

    public SecurityConfig(JwtEncoder jwtEncoder,
                          OAuth2AuthorizedClientService authorizedClientService,
                          UserTokenService userTokenService) {
        this.jwtEncoder = jwtEncoder;
        this.authorizedClientService = authorizedClientService;
        this.userTokenService = userTokenService;
    }

    // 1️⃣ Authorization Server endpoints (token, jwks, introspection)
    @Bean
    @Order(1)
    public SecurityFilterChain authServerSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/oauth2/**", "/.well-known/**");

        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);

        http.csrf(csrf -> csrf.ignoringRequestMatchers("/oauth2/token"));

        return http.build();
    }

    // 2️⃣ API endpoints secured with Bearer JWT (for frontend API calls)
    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/user/plan").authenticated()
                        .requestMatchers("/api/admin/**").authenticated()
                        .requestMatchers("/api/payment/**").authenticated()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));

        return http.build();
    }

    // 3️⃣ Application endpoints (Google OAuth login + legacy endpoints)
    @Bean
    @Order(3)
    public SecurityFilterChain appSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/h2-console/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/user-token").authenticated()
                        .requestMatchers("/user/stay-logged-in").authenticated()
                        .requestMatchers("/token.html").permitAll()
                        .requestMatchers("/oauth2/authorization/**").permitAll()
                        .requestMatchers("/token/refresh").permitAll()
                        .requestMatchers("/token/revoke").permitAll()
                        .requestMatchers("/token/info").permitAll()
                        .anyRequest().authenticated()
                )
                .headers(headers -> headers.frameOptions().disable())
                .oauth2Login(oauth -> oauth
                        .successHandler(successHandler())   // our custom handler
                );

        return http.build();
    }

    // 3️⃣ Success Handler → save tokens as HttpOnly cookies + redirect to dashboard
    @Bean
    public AuthenticationSuccessHandler successHandler() {
        return (request, response, authentication) -> {
            System.out.println("=== OAUTH LOGIN SUCCESS HANDLER ===");
            Instant now = Instant.now();
            String scope = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.joining(" "));

            OAuth2AuthenticationToken authToken = (OAuth2AuthenticationToken) authentication;

            // Principal as OidcUser (same type your userTokenService already uses)
            OidcUser user = (OidcUser) authToken.getPrincipal();

            // Load the authorized client (contains access/refresh tokens from Google)
            OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(
                    authToken.getAuthorizedClientRegistrationId(), // "google"
                    authToken.getName()
            );

            String email = user.getAttribute("email");
            String name  = user.getAttribute("name");
            String sub = user.getSubject();

            System.out.println("User: " + email + " (sub: " + sub + ")");
            System.out.println("Google Access Token: " + (client.getAccessToken() != null ? "present" : "missing"));
            System.out.println("Google Refresh Token from client: " + (client.getRefreshToken() != null ? "present" : "MISSING!"));

            long accessTokenMaxAge = 3600; // 1 hour
            Instant expiresAt = now.plus(accessTokenMaxAge, ChronoUnit.SECONDS);

            JwtClaimsSet claims = JwtClaimsSet.builder()
                    .issuer(issuer)
                    .issuedAt(now)
                    .expiresAt(expiresAt)
                    .subject(sub)
                    .claim("scope", scope)
                    .claim("email", email)
                    .claim("name", name)
                    .build();

            // Persist / update user + tokens
            userTokenService.saveOrUpdateToken(user, client);

            String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

            // Get the stored refresh token (Google's refresh token)
            String refreshToken = userTokenService.getRefreshTokenBySub(sub);
            System.out.println("Stored Refresh Token: " + (refreshToken != null ? "present" : "NOT FOUND IN DB!"));

            boolean isSecure = frontendBaseUrl.startsWith("https");

            // Set access_token as HttpOnly cookie (Path=/ so gateway sees it for all routes)
            Cookie accessCookie = new Cookie("access_token", accessToken);
            accessCookie.setHttpOnly(true);
            accessCookie.setSecure(isSecure);
            accessCookie.setPath("/");
            accessCookie.setMaxAge((int) accessTokenMaxAge);
            response.addCookie(accessCookie);

            // Set refresh_token as HttpOnly cookie (narrow path: only sent to token endpoints)
            if (refreshToken != null) {
                Cookie refreshCookie = new Cookie("refresh_token", refreshToken);
                refreshCookie.setHttpOnly(true);
                refreshCookie.setSecure(isSecure);
                refreshCookie.setPath("/oauth-service/token");
                refreshCookie.setMaxAge(2592000); // 30 days
                response.addCookie(refreshCookie);
                System.out.println("Set refresh_token cookie (path=/oauth-service/token)");
            }

            // Set token_meta as a readable (non-HttpOnly) cookie for JS to check expiry/email
            String metaValue = "exp=" + expiresAt.getEpochSecond()
                    + "&email=" + URLEncoder.encode(email != null ? email : "", StandardCharsets.UTF_8)
                    + "&name=" + URLEncoder.encode(name != null ? name : "", StandardCharsets.UTF_8);
            Cookie metaCookie = new Cookie("token_meta", metaValue);
            metaCookie.setHttpOnly(false);
            metaCookie.setSecure(isSecure);
            metaCookie.setPath("/");
            metaCookie.setMaxAge((int) accessTokenMaxAge);
            response.addCookie(metaCookie);

            System.out.println("Cookies set, redirecting to dashboard");
            response.sendRedirect(frontendBaseUrl + "/dashboard.html");
        };
    }

    // 4️⃣ Debug logging filter (unchanged)
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> chainLogger() {
        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain filterChain)
                    throws ServletException, IOException {
                System.out.println("➡️ Request " + request.getRequestURI());
                filterChain.doFilter(request, response);
            }
        });
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    // 5️⃣ Registered client for reporting-service (unchanged)
    @Bean
    public RegisteredClientRepository registeredClientRepository() {
        RegisteredClient registeredClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("reporting-client")
                .clientSecret("{noop}secret")
                .scope("read")
                .scope("write")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .redirectUri("http://localhost:8082/reporting-service/login/oauth2/code/reporting-client-oidc")
                .redirectUri("http://localhost:8082/reporting-service/authorized")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .build())
                .build();

        return new InMemoryRegisteredClientRepository(registeredClient);
    }

    // 6️⃣ Authorization Server settings
    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().build();
    }
}



