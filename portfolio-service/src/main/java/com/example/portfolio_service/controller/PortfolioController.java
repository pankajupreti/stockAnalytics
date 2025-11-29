package com.example.portfolio_service.controller;




import com.example.portfolio_service.dto.HoldingDTO;
import com.example.portfolio_service.dto.PortfolioSummaryDTO;
import com.example.portfolio_service.dto.PositionRequest;
import com.example.portfolio_service.dto.QuoteDTO;
import com.example.portfolio_service.model.Position;
import com.example.portfolio_service.quotes.QuotesClient;
import com.example.portfolio_service.security.CurrentUser;
import com.example.portfolio_service.service.PortfolioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioService service;
    private final CurrentUser current;
    private final QuotesClient quotesClient;

    // ----- Positions CRUD -----
    @GetMapping("/positions")
    public List<Position> list(Authentication auth) {
        return service.list(current.sub(auth));
    }

    @PostMapping("/positions")
    public ResponseEntity<Position> create(@Valid @RequestBody PositionRequest req,
                                           @AuthenticationPrincipal Jwt jwt) {
        // Validate ticker
        var sym = quotesClient.resolve(req.getTicker(), jwt.getTokenValue()).blockOptional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown ticker"));

        Position p = Position.builder()
                .userSub(jwt.getSubject())
                .ticker(sym.getTicker())   // normalized/canonical
                .quantity(req.getQuantity())
                .buyPrice(req.getBuyPrice())
                .buyDate(req.getBuyDate())
                .notes(req.getNotes())
                .build();
        Position p1 = service.create(jwt.getSubject(), req);
        return ResponseEntity.created(URI.create("/api/portfolio/positions/" + p1.getId()))
                .body(p);
    }

    @PutMapping("/positions/{id}")
    public ResponseEntity<Position> update(@PathVariable Long id,
                                           @Valid @RequestBody PositionRequest req,
                                           @AuthenticationPrincipal Jwt jwt) {
        // 1) Fetch existing record scoped to current user
        Position existing = service.findByIdAndUserSub(id, jwt.getSubject())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Position not found"));

        var sym = quotesClient.resolve(req.getTicker(), jwt.getTokenValue())
                .blockOptional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown ticker"));

/*        // 2) Validate + normalize ticker via Quote API
        var canonical = quotesClient.resolve(req.getTicker())
                .blockOptional()
                .map(QuoteDTO::getTicker)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown ticker"));*/

        // 3) Apply updates
        existing.setTicker(req.getTicker());
        existing.setQuantity(req.getQuantity());
        existing.setBuyPrice(req.getBuyPrice());
        existing.setBuyDate(req.getBuyDate());
        existing.setNotes(req.getNotes());

        // 4) Persist
        Position saved = service.update(jwt.getSubject(), id, req);


        // 5) Return
        return ResponseEntity.ok(saved);
    }


    @DeleteMapping("/positions/{id}")
    public ResponseEntity<Void> delete(Authentication auth, @PathVariable Long id) {
        service.delete(current.sub(auth), id);
        return ResponseEntity.noContent().build();
    }

    // ----- Enriched holdings + summary -----
    @GetMapping("/holdings")
    public List<HoldingDTO> holdings(Authentication auth) {
        return service.holdings(current.sub(auth));
    }

    @GetMapping("/summary")
    public PortfolioSummaryDTO summary(Authentication auth) {
        return service.summary(current.sub(auth));
    }
}
