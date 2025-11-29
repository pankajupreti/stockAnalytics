package com.example.demo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity

public class SecurityConfig {

    private final JwtEncoder jwtEncoder;

    public SecurityConfig(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
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

    // 2️⃣ Application endpoints (stateless, only Google login)
    @Bean
    @Order(2)
    public SecurityFilterChain appSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())  // 🚫 disable default login page
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS) // no JSESSIONID
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/h2-console/**").permitAll()
                        .requestMatchers("/user-token").authenticated()
                        .requestMatchers("/token.html").permitAll()
                      //  .requestMatchers("/", "/index.html", "/styles.css", "/app.js").permitAll()
                        .requestMatchers("/oauth2/authorization/**").permitAll() // ✅ entry for Google OAuth
                        .anyRequest().authenticated()
                )
                .headers(headers -> headers.frameOptions().disable())
                .oauth2Login(oauth -> oauth
                        .successHandler(successHandler()) // redirect instead of raw JSON
                );

        return http.build();
    }

    // 3️⃣ Success Handler → redirect back to SPA
    @Bean

    public AuthenticationSuccessHandler successHandler() {
        return (request, response, authentication) -> {
            Instant now = Instant.now();
            String scope = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.joining(" "));
            // 👇 extract OAuth2 user attributes
            OAuth2AuthenticationToken authToken = (OAuth2AuthenticationToken) authentication;
            OAuth2User principal = authToken.getPrincipal();

            String email = principal.getAttribute("email");
            String name  = principal.getAttribute("name");

            JwtClaimsSet claims = JwtClaimsSet.builder()
                    .issuer("http://localhost:8080")
                    .issuedAt(now)
                    .expiresAt(now.plus(4, ChronoUnit.HOURS))
                    .subject(authentication.getName())  // Google subject ID
                    .claim("scope", scope)
                    .claim("email", email)
                    .claim("name", name)
                    .build();

            String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
            System.out.println("printing access token "+token);
            // redirect to SPA with token in fragment
            response.sendRedirect("http://localhost:8082/index.html#access_token=" + token);

        };
    }


    // 4️⃣ Debug logging filter (optional)
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> chainLogger() {
        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                    throws ServletException, IOException {
                System.out.println("➡️ Request " + request.getRequestURI());
                filterChain.doFilter(request, response);
            }
        });
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    // 5️⃣ Register OAuth client (reporting-service)
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


