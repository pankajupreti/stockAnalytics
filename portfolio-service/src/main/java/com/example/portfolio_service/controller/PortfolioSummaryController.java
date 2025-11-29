package com.example.portfolio_service.controller;

import com.example.portfolio_service.ai.PortfolioSummaryDTO;
import com.example.portfolio_service.ai.SummaryOrchestrator;
import com.example.portfolio_service.ai.SummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// src/main/java/.../web/PortfolioSummaryController.java
@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
public class PortfolioSummaryController {

    private final SummaryService factsService;
    private final SummaryOrchestrator orchestrator;

    @GetMapping("/ai-summary")
    @Cacheable(value="aiSummary", key="#jwt.subject")
    public ResponseEntity<PortfolioSummaryDTO> aiSummary(@AuthenticationPrincipal Jwt jwt,
                                                            @RequestHeader("Authorization") String bearer) {
        var token = bearer.replaceFirst("Bearer\\s+","").trim();
        var f = factsService.computeFacts(jwt.getSubject(), token);
        var text = orchestrator.generate(f);
        boolean ai = orchestrator.usedAi() && text != null;

        var dto = new PortfolioSummaryDTO(
                f.dayPercent(), f.dayValue(),
                f.leaders(), f.laggards(),
                text != null ? text : "", ai);

        return ResponseEntity.ok(dto);
    }
}
