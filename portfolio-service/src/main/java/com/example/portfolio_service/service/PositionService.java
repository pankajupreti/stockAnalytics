/*
package com.example.portfolio_service.service;


import com.example.portfolio_service.dto.PositionRequest;
import com.example.portfolio_service.dto.PositionResponse;
import com.example.portfolio_service.model.Position;
import com.example.portfolio_service.repository.PositionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PositionService {

    private final PositionRepository repo;

    public PositionService(PositionRepository repo) { this.repo = repo; }

*/
/*    @Transactional
    public PositionResponse create(String userId, PositionRequest req) {
        Position p = new Position();
        p.setUserId(userId);
        p.setTicker(req.getTicker().trim().toUpperCase());
        p.setQuantity(req.getQuantity());
        p.setBuyPrice(req.getBuyPrice());
        p.setBuyDate(req.getBuyDate());
        p.setNotes(req.getNotes());
        p = repo.save(p);
        return toResp(p);
    }*//*


    @Transactional
    public PositionResponse update(String userId, Long id, PositionRequest req) {
        Position p = repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Position not found"));
        p.setTicker(req.getTicker().trim().toUpperCase());
        p.setQuantity(req.getQuantity());
        p.setBuyPrice(req.getBuyPrice());
        p.setBuyDate(req.getBuyDate());
        p.setNotes(req.getNotes());
        return toResp(p);
    }

    @Transactional(readOnly = true)
    public List<PositionResponse> list(String userId) {
        return repo.findAllByUserIdOrderByBuyDateAscIdAsc(userId)
                .stream().map(this::toResp).collect(Collectors.toList());
    }

    @Transactional
    public void delete(String userId, Long id) {
        Position p = repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Position not found"));
        repo.delete(p);
    }

    private PositionResponse toResp(Position p) {
        PositionResponse r = new PositionResponse();
        r.setId(p.getId());
        r.setTicker(p.getTicker());
        r.setQuantity(p.getQuantity());
        r.setBuyPrice(p.getBuyPrice());
        r.setBuyDate(p.getBuyDate());
        r.setNotes(p.getNotes());
        r.setInvested(p.getBuyPrice().multiply(BigDecimal.valueOf(p.getQuantity())));
        return r;
    }
}
*/
