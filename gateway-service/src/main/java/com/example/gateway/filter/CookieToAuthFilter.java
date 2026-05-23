package com.example.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFilter that runs BEFORE Spring Security.
 * Extracts access_token from HttpOnly cookie and injects it as
 * an Authorization: Bearer header so the gateway's oauth2ResourceServer(jwt)
 * and downstream services see a valid Bearer token.
 *
 * Must run at a very low order (high priority) so it executes before
 * SecurityWebFilterChain (which defaults to order -100).
 */
@Component
public class CookieToAuthFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CookieToAuthFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Skip if Authorization header already present (e.g. service-to-service calls)
        if (request.getHeaders().containsKey("Authorization")) {
            return chain.filter(exchange);
        }

        // Extract access_token cookie
        HttpCookie cookie = request.getCookies().getFirst("access_token");
        if (cookie != null && !cookie.getValue().isEmpty()) {
            log.debug("CookieToAuthFilter: injecting Bearer token from cookie for {}", request.getPath());
            ServerHttpRequest mutated = request.mutate()
                    .header("Authorization", "Bearer " + cookie.getValue())
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // Spring Security's WebFilterChain runs at order -100.
        // We must run before it to inject the Authorization header.
        return -200;
    }
}
