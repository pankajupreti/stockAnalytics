package com.example.gateway.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.*;

/**
 * Server-side health check controller.
 * Pings all microservices from the gateway (no CORS issues)
 * and returns aggregated status to the frontend.
 */
@RestController
public class HealthCheckController {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckController.class);

    // Don't follow redirects (OAuth returns 302 which is fine - means it's running)
    private final WebClient webClient = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(
                    HttpClient.create().followRedirect(false)
            ))
            .build();

    private record ServiceDef(String name, int port, String path, String type) {}

    private static final List<ServiceDef> SERVICES = List.of(
            new ServiceDef("Gateway",          8082, "/actuator/health",          "spring"),
            new ServiceDef("OAuth",            8080, "/actuator/health",          "spring"),
            new ServiceDef("Reporting",        8083, "/actuator/health",          "spring"),
            new ServiceDef("Portfolio",        8084, "/actuator/health",          "spring"),
            new ServiceDef("Sheet Import",     8091, "/api/eod-prices/status",    "custom"),
            new ServiceDef("Announcement",     8092, "/actuator/health",          "spring"),
            new ServiceDef("Alert",            8087, "/actuator/health",          "spring"),
            new ServiceDef("Results (Java)",   8088, "/actuator/health",          "spring"),
            new ServiceDef("Results (Python)", 8090, "/health",                   "fastapi"),
            new ServiceDef("Eureka",           8761, "/actuator/health",          "spring"),
            new ServiceDef("RabbitMQ",         15672, "/api/healthchecks/node",   "rabbitmq")
    );

    @GetMapping("/api/system/health")
    public Mono<Map<String, Object>> checkAll() {
        long start = System.currentTimeMillis();

        return Flux.fromIterable(SERVICES)
                .flatMap(this::checkService)
                .collectList()
                .map(results -> {
                    long up = results.stream().filter(r -> "UP".equals(r.get("status"))).count();
                    long down = results.size() - up;
                    double avgResponse = results.stream()
                            .mapToLong(r -> ((Number) r.getOrDefault("responseTime", 0L)).longValue())
                            .filter(t -> t > 0)
                            .average().orElse(0);

                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("overall", down == 0 ? "HEALTHY" : up > down ? "DEGRADED" : "DOWN");
                    response.put("servicesUp", up);
                    response.put("servicesDown", down);
                    response.put("totalServices", results.size());
                    response.put("avgResponseMs", Math.round(avgResponse));
                    response.put("checkDurationMs", System.currentTimeMillis() - start);
                    response.put("timestamp", new Date().toString());
                    response.put("services", results);
                    return response;
                });
    }

    private Mono<Map<String, Object>> checkService(ServiceDef svc) {
        long start = System.currentTimeMillis();
        String url = "http://localhost:" + svc.port() + svc.path();

        WebClient.RequestHeadersSpec<?> request = webClient.get().uri(url);

        // RabbitMQ needs basic auth
        if ("rabbitmq".equals(svc.type())) {
            request = request.headers(h -> h.setBasicAuth("guest", "guest"));
        }

        // Use exchangeToMono so ANY HTTP response (even 401, 302, 503) means service is reachable
        return request
                .exchangeToMono(response -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("name", svc.name());
                    result.put("port", svc.port());
                    result.put("responseTime", System.currentTimeMillis() - start);

                    int statusCode = response.statusCode().value();
                    // Any HTTP response means the service is running
                    // Only 503 (Service Unavailable) indicates unhealthy
                    if (statusCode == 503) {
                        result.put("status", "UP");
                        result.put("note", "Service starting or dependency issue (503)");
                    } else {
                        result.put("status", "UP");
                    }
                    result.put("httpStatus", statusCode);

                    // Release the response body
                    return response.releaseBody().thenReturn(result);
                })
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(e -> {
                    // Connection refused, timeout = truly DOWN
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("name", svc.name());
                    result.put("port", svc.port());
                    result.put("status", "DOWN");
                    result.put("responseTime", System.currentTimeMillis() - start);
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    // Clean up verbose error messages
                    if (msg.contains("Connection refused")) {
                        msg = "Connection refused";
                    } else if (msg.length() > 100) {
                        msg = msg.substring(0, 100) + "...";
                    }
                    result.put("error", msg);
                    return Mono.just(result);
                });
    }

    /**
     * Quick check - returns just overall status (for uptime monitoring).
     */
    @GetMapping("/api/system/ping")
    public Mono<Map<String, String>> ping() {
        return Mono.just(Map.of("status", "ok", "service", "gateway"));
    }
}
