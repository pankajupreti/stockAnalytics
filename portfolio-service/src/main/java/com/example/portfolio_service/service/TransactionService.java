package com.example.portfolio_service.service;

import com.example.portfolio_service.dto.AddSharesRequest;
import com.example.portfolio_service.dto.SellRequest;
import com.example.portfolio_service.dto.TransactionDTO;
import com.example.portfolio_service.model.Position;
import com.example.portfolio_service.model.Transaction;
import com.example.portfolio_service.model.Transaction.TransactionType;
import com.example.portfolio_service.repository.PositionRepository;
import com.example.portfolio_service.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final PositionRepository positionRepository;

    /**
     * Record a BUY transaction and update/create position
     */
    @Transactional
    public Transaction addShares(String userSub, AddSharesRequest req) {
        String ticker = req.getTicker().toUpperCase(Locale.ROOT).trim();
        LocalDate buyDate = req.getBuyDate() != null ? req.getBuyDate() : LocalDate.now();

        // Find existing position - try with and without NSE: prefix
        Position existing = positionRepository.findByUserSubAndTicker(userSub, ticker).orElse(null);

        if (existing == null && !ticker.contains(":")) {
            // Try with NSE: prefix
            existing = positionRepository.findByUserSubAndTicker(userSub, "NSE:" + ticker).orElse(null);
            if (existing != null) {
                ticker = "NSE:" + ticker;
            }
        }

        if (existing == null && !ticker.contains(":")) {
            // Try with BSE: prefix
            existing = positionRepository.findByUserSubAndTicker(userSub, "BSE:" + ticker).orElse(null);
            if (existing != null) {
                ticker = "BSE:" + ticker;
            }
        }

        // If no existing position, add NSE: prefix for new positions
        if (existing == null && !ticker.contains(":")) {
            ticker = "NSE:" + ticker;
        }

        // Create transaction record with resolved ticker
        Transaction tx = Transaction.builder()
                .userSub(userSub)
                .ticker(ticker)
                .type(TransactionType.BUY)
                .quantity(req.getQuantity())
                .price(req.getBuyPrice())
                .transactionDate(buyDate)
                .notes(req.getNotes())
                .build();
        tx = transactionRepository.save(tx);

        if (existing != null) {
            // Calculate new weighted average buy price
            BigDecimal oldValue = existing.getBuyPrice().multiply(BigDecimal.valueOf(existing.getQuantity()));
            BigDecimal newValue = req.getBuyPrice().multiply(BigDecimal.valueOf(req.getQuantity()));
            int newQty = existing.getQuantity() + req.getQuantity();

            BigDecimal newAvgPrice = oldValue.add(newValue)
                    .divide(BigDecimal.valueOf(newQty), 4, RoundingMode.HALF_UP);

            existing.setQuantity(newQty);
            existing.setBuyPrice(newAvgPrice);
            // Update buy date to latest if new purchase is later
            if (existing.getBuyDate() == null || buyDate.isAfter(existing.getBuyDate())) {
                existing.setBuyDate(buyDate);
            }
            if (req.getNotes() != null && !req.getNotes().isBlank()) {
                existing.setNotes(req.getNotes());
            }
            positionRepository.save(existing);
        } else {
            // Create new position
            Position newPos = Position.builder()
                    .userSub(userSub)
                    .ticker(ticker)
                    .quantity(req.getQuantity())
                    .buyPrice(req.getBuyPrice())
                    .buyDate(buyDate)
                    .notes(req.getNotes())
                    .build();
            positionRepository.save(newPos);
        }

        log.info("Added {} shares of {} for user {} at price {}",
                req.getQuantity(), ticker, userSub, req.getBuyPrice());

        return tx;
    }

    /**
     * Record a SELL transaction and update position
     */
    @Transactional
    public Transaction sellShares(String userSub, SellRequest req) {
        String ticker = req.getTicker().toUpperCase(Locale.ROOT).trim();
        LocalDate sellDate = req.getSellDate() != null ? req.getSellDate() : LocalDate.now();

        // Find existing position - try with and without NSE: prefix
        Position position = positionRepository.findByUserSubAndTicker(userSub, ticker).orElse(null);

        if (position == null && !ticker.contains(":")) {
            // Try with NSE: prefix
            position = positionRepository.findByUserSubAndTicker(userSub, "NSE:" + ticker).orElse(null);
            if (position != null) {
                ticker = "NSE:" + ticker;
            }
        }

        if (position == null && !ticker.contains(":")) {
            // Try with BSE: prefix
            position = positionRepository.findByUserSubAndTicker(userSub, "BSE:" + ticker).orElse(null);
            if (position != null) {
                ticker = "BSE:" + ticker;
            }
        }

        if (position == null) {
            throw new IllegalArgumentException("No position found for ticker: " + req.getTicker());
        }

        // Validate quantity
        if (req.getQuantity() > position.getQuantity()) {
            throw new IllegalArgumentException(
                    "Cannot sell " + req.getQuantity() + " shares. Only " + position.getQuantity() + " available.");
        }

        // Calculate realized P&L
        BigDecimal avgBuyPrice = position.getBuyPrice();
        BigDecimal sellValue = req.getSellPrice().multiply(BigDecimal.valueOf(req.getQuantity()));
        BigDecimal buyValue = avgBuyPrice.multiply(BigDecimal.valueOf(req.getQuantity()));
        BigDecimal realizedPnl = sellValue.subtract(buyValue);

        // Create transaction record
        Transaction tx = Transaction.builder()
                .userSub(userSub)
                .ticker(ticker)
                .type(TransactionType.SELL)
                .quantity(req.getQuantity())
                .price(req.getSellPrice())
                .transactionDate(sellDate)
                .realizedPnl(realizedPnl)
                .avgBuyPriceAtSell(avgBuyPrice)
                .notes(req.getNotes())
                .build();
        tx = transactionRepository.save(tx);

        // Update position quantity
        int remainingQty = position.getQuantity() - req.getQuantity();
        if (remainingQty == 0) {
            // Fully sold - delete position
            positionRepository.delete(position);
            log.info("Fully sold {} shares of {} for user {}. Position deleted.",
                    req.getQuantity(), ticker, userSub);
        } else {
            // Partial sell - update quantity (buy price remains same as weighted avg)
            position.setQuantity(remainingQty);
            positionRepository.save(position);
            log.info("Sold {} shares of {} for user {}. {} shares remaining.",
                    req.getQuantity(), ticker, userSub, remainingQty);
        }

        log.info("Realized P&L for this sell: {}", realizedPnl);

        return tx;
    }

    /**
     * Get all transactions for a user
     */
    public List<TransactionDTO> getTransactions(String userSub) {
        return transactionRepository.findByUserSubOrderByTransactionDateDescCreatedAtDesc(userSub)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get only SELL transactions (for P&L report)
     */
    public List<TransactionDTO> getSellTransactions(String userSub) {
        return transactionRepository.findSellTransactions(userSub)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get SELL transactions within date range
     */
    public List<TransactionDTO> getSellTransactionsInRange(String userSub, LocalDate from, LocalDate to) {
        return transactionRepository.findSellTransactionsInRange(userSub, from, to)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get total realized P&L for user
     */
    public BigDecimal getTotalRealizedPnl(String userSub) {
        return transactionRepository.getTotalRealizedPnl(userSub);
    }

    /**
     * Get realized P&L for user within date range
     */
    public BigDecimal getRealizedPnlInRange(String userSub, LocalDate from, LocalDate to) {
        return transactionRepository.getRealizedPnlInRange(userSub, from, to);
    }

    /**
     * Migrate existing position to transaction (for one-time migration)
     */
    @Transactional
    public Transaction migratePositionToTransaction(Position position) {
        // Check if already migrated
        if (transactionRepository.existsByPositionId(position.getId())) {
            log.info("Position {} already migrated", position.getId());
            return null;
        }

        Transaction tx = Transaction.builder()
                .userSub(position.getUserSub())
                .ticker(position.getTicker())
                .type(TransactionType.BUY)
                .quantity(position.getQuantity())
                .price(position.getBuyPrice())
                .transactionDate(position.getBuyDate() != null ? position.getBuyDate() : LocalDate.now())
                .notes(position.getNotes())
                .positionId(position.getId())
                .build();

        return transactionRepository.save(tx);
    }

    /**
     * Migrate all positions for a user (one-time migration)
     */
    @Transactional
    public int migrateAllPositions(String userSub) {
        List<Position> positions = positionRepository.findByUserSubOrderByIdAsc(userSub);
        int migrated = 0;

        for (Position position : positions) {
            Transaction tx = migratePositionToTransaction(position);
            if (tx != null) {
                migrated++;
                log.info("Migrated position {} ({}) to transaction", position.getId(), position.getTicker());
            }
        }

        log.info("Migration completed for user {}. Migrated {} positions.", userSub, migrated);
        return migrated;
    }

    private TransactionDTO toDTO(Transaction tx) {
        return TransactionDTO.builder()
                .id(tx.getId())
                .ticker(tx.getTicker())
                .type(tx.getType().name())
                .quantity(tx.getQuantity())
                .price(tx.getPrice())
                .transactionDate(tx.getTransactionDate())
                .createdAt(tx.getCreatedAt())
                .realizedPnl(tx.getRealizedPnl())
                .avgBuyPriceAtSell(tx.getAvgBuyPriceAtSell())
                .notes(tx.getNotes())
                .positionId(tx.getPositionId())
                .totalValue(tx.getPrice().multiply(BigDecimal.valueOf(tx.getQuantity())))
                .build();
    }
}
