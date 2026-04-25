package com.example.portfolio_service.service;

import com.example.portfolio_service.dto.HoldingDTO;
import com.example.portfolio_service.dto.PortfolioAnalyticsDTO;
import com.example.portfolio_service.dto.PortfolioAnalyticsDTO.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PortfolioAnalyticsService {

    private final PortfolioService portfolioService;

    public PortfolioAnalyticsDTO getAnalytics(String userSub) {
        return getAnalytics(userSub, null);
    }

    public PortfolioAnalyticsDTO getAnalytics(String userSub, String jwtToken) {
        List<HoldingDTO> holdings = portfolioService.holdings(userSub, jwtToken);

        if (holdings.isEmpty()) {
            return PortfolioAnalyticsDTO.builder()
                    .totalPositions(0)
                    .totalInvested(BigDecimal.ZERO)
                    .totalCurrentValue(BigDecimal.ZERO)
                    .totalPnlAbs(BigDecimal.ZERO)
                    .totalPnlPct(BigDecimal.ZERO)
                    .sectorAllocations(List.of())
                    .topPerformers(List.of())
                    .bottomPerformers(List.of())
                    .largestHoldings(List.of())
                    .near52WeekHigh(List.of())
                    .near52WeekLow(List.of())
                    .concentrationRisk(BigDecimal.ZERO)
                    .sectorCount(0)
                    .build();
        }

        // Calculate totals - only include holdings with valid prices (non-null pnlAbs)
        // This ensures consistency with frontend which excludes stocks without prices
        BigDecimal totalInvested = holdings.stream()
                .filter(h -> h.getPnlAbs() != null)  // Only include if price is available
                .map(HoldingDTO::getBuyValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCurrentValue = holdings.stream()
                .filter(h -> h.getPnlAbs() != null)  // Only include if price is available
                .map(HoldingDTO::getMarketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPnlAbs = holdings.stream()
                .filter(h -> h.getPnlAbs() != null)
                .map(HoldingDTO::getPnlAbs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPnlPct = totalInvested.signum() == 0 ? BigDecimal.ZERO :
                totalPnlAbs.multiply(BigDecimal.valueOf(100))
                        .divide(totalInvested, 2, RoundingMode.HALF_UP);

        // Sector allocations
        List<SectorAllocation> sectorAllocations = calculateSectorAllocations(holdings, totalCurrentValue);

        // Top/Bottom performers
        List<HoldingPerformance> topPerformers = holdings.stream()
                .filter(h -> h.getPnlPct() != null)
                .sorted(Comparator.comparing(HoldingDTO::getPnlPct).reversed())
                .limit(5)
                .map(this::toHoldingPerformance)
                .collect(Collectors.toList());

        List<HoldingPerformance> bottomPerformers = holdings.stream()
                .filter(h -> h.getPnlPct() != null)
                .sorted(Comparator.comparing(HoldingDTO::getPnlPct))
                .limit(5)
                .map(this::toHoldingPerformance)
                .collect(Collectors.toList());

        // Largest holdings by market value
        List<HoldingPerformance> largestHoldings = holdings.stream()
                .sorted(Comparator.comparing(HoldingDTO::getMarketValue).reversed())
                .limit(5)
                .map(this::toHoldingPerformance)
                .collect(Collectors.toList());

        // 52-week analysis
        List<Week52Analysis> near52WeekHigh = calculate52WeekHighProximity(holdings);
        List<Week52Analysis> near52WeekLow = calculate52WeekLowProximity(holdings);

        // Concentration risk (top 3 holdings as % of portfolio)
        BigDecimal top3Value = holdings.stream()
                .sorted(Comparator.comparing(HoldingDTO::getMarketValue).reversed())
                .limit(3)
                .map(HoldingDTO::getMarketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal concentrationRisk = totalCurrentValue.signum() == 0 ? BigDecimal.ZERO :
                top3Value.multiply(BigDecimal.valueOf(100))
                        .divide(totalCurrentValue, 2, RoundingMode.HALF_UP);

        // Sector count
        int sectorCount = (int) holdings.stream()
                .map(HoldingDTO::getSector)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        // Stock weightages
        List<PortfolioAnalyticsDTO.StockWeightage> stockWeightages = calculateStockWeightages(holdings, totalCurrentValue);

        // Winners vs Losers
        PortfolioAnalyticsDTO.WinnersLosers winnersLosers = calculateWinnersLosers(holdings);

        // Momentum Analysis
        PortfolioAnalyticsDTO.MomentumAnalysis momentumAnalysis = calculateMomentumAnalysis(holdings);

        return PortfolioAnalyticsDTO.builder()
                .totalPositions(holdings.size())
                .totalInvested(totalInvested)
                .totalCurrentValue(totalCurrentValue)
                .totalPnlAbs(totalPnlAbs)
                .totalPnlPct(totalPnlPct)
                .sectorAllocations(sectorAllocations)
                .stockWeightages(stockWeightages)
                .topPerformers(topPerformers)
                .bottomPerformers(bottomPerformers)
                .largestHoldings(largestHoldings)
                .near52WeekHigh(near52WeekHigh)
                .near52WeekLow(near52WeekLow)
                .concentrationRisk(concentrationRisk)
                .sectorCount(sectorCount)
                .winnersLosers(winnersLosers)
                .momentumAnalysis(momentumAnalysis)
                .build();
    }

    private List<SectorAllocation> calculateSectorAllocations(List<HoldingDTO> holdings, BigDecimal totalValue) {
        Map<String, List<HoldingDTO>> bySector = holdings.stream()
                .collect(Collectors.groupingBy(h -> h.getSector() != null ? h.getSector() : "Unknown"));

        return bySector.entrySet().stream()
                .map(e -> {
                    String sector = e.getKey();
                    List<HoldingDTO> sectorHoldings = e.getValue();

                    BigDecimal sectorValue = sectorHoldings.stream()
                            .map(HoldingDTO::getMarketValue)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    BigDecimal sectorBuyValue = sectorHoldings.stream()
                            .map(HoldingDTO::getBuyValue)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    BigDecimal sectorPnlAbs = sectorValue.subtract(sectorBuyValue);
                    BigDecimal sectorPnlPct = sectorBuyValue.signum() == 0 ? BigDecimal.ZERO :
                            sectorPnlAbs.multiply(BigDecimal.valueOf(100))
                                    .divide(sectorBuyValue, 2, RoundingMode.HALF_UP);

                    BigDecimal percentage = totalValue.signum() == 0 ? BigDecimal.ZERO :
                            sectorValue.multiply(BigDecimal.valueOf(100))
                                    .divide(totalValue, 2, RoundingMode.HALF_UP);

                    return SectorAllocation.builder()
                            .sector(sector)
                            .holdingsCount(sectorHoldings.size())
                            .marketValue(sectorValue)
                            .percentage(percentage)
                            .pnlAbs(sectorPnlAbs)
                            .pnlPct(sectorPnlPct)
                            .build();
                })
                .sorted(Comparator.comparing(SectorAllocation::getPercentage).reversed())
                .collect(Collectors.toList());
    }

    private HoldingPerformance toHoldingPerformance(HoldingDTO h) {
        return HoldingPerformance.builder()
                .ticker(h.getTicker())
                .name(h.getName())
                .sector(h.getSector())
                .marketValue(h.getMarketValue())
                .pnlAbs(h.getPnlAbs())
                .pnlPct(h.getPnlPct())
                .build();
    }

    private List<PortfolioAnalyticsDTO.StockWeightage> calculateStockWeightages(List<HoldingDTO> holdings, BigDecimal totalValue) {
        return holdings.stream()
                .map(h -> {
                    BigDecimal weightPct = totalValue.signum() == 0 ? BigDecimal.ZERO :
                            h.getMarketValue().multiply(BigDecimal.valueOf(100))
                                    .divide(totalValue, 2, RoundingMode.HALF_UP);

                    return PortfolioAnalyticsDTO.StockWeightage.builder()
                            .ticker(h.getTicker())
                            .name(h.getName())
                            .sector(h.getSector())
                            .currentValue(h.getMarketValue())
                            .weightPct(weightPct)
                            .pnlPct(h.getPnlPct())
                            .build();
                })
                .sorted(Comparator.comparing(PortfolioAnalyticsDTO.StockWeightage::getWeightPct).reversed())
                .collect(Collectors.toList());
    }

    private List<Week52Analysis> calculate52WeekHighProximity(List<HoldingDTO> holdings) {
        return holdings.stream()
                .filter(h -> h.getHigh52Week() != null && h.getCmp() != null)
                .map(h -> {
                    BigDecimal pctFromHigh = h.getCmp().subtract(h.getHigh52Week())
                            .multiply(BigDecimal.valueOf(100))
                            .divide(h.getHigh52Week(), 2, RoundingMode.HALF_UP);

                    BigDecimal pctFromLow = h.getLow52Week() != null && h.getLow52Week().signum() > 0 ?
                            h.getCmp().subtract(h.getLow52Week())
                                    .multiply(BigDecimal.valueOf(100))
                                    .divide(h.getLow52Week(), 2, RoundingMode.HALF_UP)
                            : null;

                    return Week52Analysis.builder()
                            .ticker(h.getTicker())
                            .name(h.getName())
                            .cmp(h.getCmp())
                            .high52Week(h.getHigh52Week())
                            .low52Week(h.getLow52Week())
                            .pctFromHigh(pctFromHigh)
                            .pctFromLow(pctFromLow)
                            .build();
                })
                .filter(a -> a.getPctFromHigh().compareTo(BigDecimal.valueOf(-5)) >= 0) // within 5% of high
                .sorted(Comparator.comparing(Week52Analysis::getPctFromHigh).reversed())
                .limit(10)
                .collect(Collectors.toList());
    }

    private List<Week52Analysis> calculate52WeekLowProximity(List<HoldingDTO> holdings) {
        return holdings.stream()
                .filter(h -> h.getLow52Week() != null && h.getCmp() != null && h.getLow52Week().signum() > 0)
                .map(h -> {
                    BigDecimal pctFromLow = h.getCmp().subtract(h.getLow52Week())
                            .multiply(BigDecimal.valueOf(100))
                            .divide(h.getLow52Week(), 2, RoundingMode.HALF_UP);

                    BigDecimal pctFromHigh = h.getHigh52Week() != null ?
                            h.getCmp().subtract(h.getHigh52Week())
                                    .multiply(BigDecimal.valueOf(100))
                                    .divide(h.getHigh52Week(), 2, RoundingMode.HALF_UP)
                            : null;

                    return Week52Analysis.builder()
                            .ticker(h.getTicker())
                            .name(h.getName())
                            .cmp(h.getCmp())
                            .high52Week(h.getHigh52Week())
                            .low52Week(h.getLow52Week())
                            .pctFromHigh(pctFromHigh)
                            .pctFromLow(pctFromLow)
                            .build();
                })
                .filter(a -> a.getPctFromLow().compareTo(BigDecimal.valueOf(10)) <= 0) // within 10% of low
                .sorted(Comparator.comparing(Week52Analysis::getPctFromLow))
                .limit(10)
                .collect(Collectors.toList());
    }

    private PortfolioAnalyticsDTO.WinnersLosers calculateWinnersLosers(List<HoldingDTO> holdings) {
        List<HoldingDTO> winners = holdings.stream()
                .filter(h -> h.getPnlPct() != null && h.getPnlPct().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());

        List<HoldingDTO> losers = holdings.stream()
                .filter(h -> h.getPnlPct() != null && h.getPnlPct().compareTo(BigDecimal.ZERO) < 0)
                .collect(Collectors.toList());

        List<HoldingDTO> breakEven = holdings.stream()
                .filter(h -> h.getPnlPct() != null && h.getPnlPct().compareTo(BigDecimal.ZERO) == 0)
                .collect(Collectors.toList());

        BigDecimal winnersValue = winners.stream()
                .map(HoldingDTO::getMarketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal losersValue = losers.stream()
                .map(HoldingDTO::getMarketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal winnersPnl = winners.stream()
                .map(HoldingDTO::getPnlAbs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal losersPnl = losers.stream()
                .map(HoldingDTO::getPnlAbs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal avgWinnerPct = winners.isEmpty() ? BigDecimal.ZERO :
                winners.stream()
                        .map(HoldingDTO::getPnlPct)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(winners.size()), 2, RoundingMode.HALF_UP);

        BigDecimal avgLoserPct = losers.isEmpty() ? BigDecimal.ZERO :
                losers.stream()
                        .map(HoldingDTO::getPnlPct)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(losers.size()), 2, RoundingMode.HALF_UP);

        int total = holdings.size();
        BigDecimal winRate = total == 0 ? BigDecimal.ZERO :
                BigDecimal.valueOf(winners.size())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);

        return PortfolioAnalyticsDTO.WinnersLosers.builder()
                .winnersCount(winners.size())
                .losersCount(losers.size())
                .breakEvenCount(breakEven.size())
                .winnersValue(winnersValue)
                .losersValue(losersValue)
                .winnersPnl(winnersPnl)
                .losersPnl(losersPnl)
                .avgWinnerPct(avgWinnerPct)
                .avgLoserPct(avgLoserPct)
                .winRate(winRate)
                .build();
    }

    private PortfolioAnalyticsDTO.MomentumAnalysis calculateMomentumAnalysis(List<HoldingDTO> holdings) {
        // Daily momentum counts
        int dailyPositive = (int) holdings.stream()
                .filter(h -> h.getDailyChange() != null && h.getDailyChange().compareTo(BigDecimal.ZERO) > 0)
                .count();
        int dailyNegative = (int) holdings.stream()
                .filter(h -> h.getDailyChange() != null && h.getDailyChange().compareTo(BigDecimal.ZERO) < 0)
                .count();
        int dailyNeutral = (int) holdings.stream()
                .filter(h -> h.getDailyChange() == null || h.getDailyChange().compareTo(BigDecimal.ZERO) == 0)
                .count();

        // Weekly momentum counts
        int weeklyPositive = (int) holdings.stream()
                .filter(h -> h.getWeeklyChange() != null && h.getWeeklyChange().compareTo(BigDecimal.ZERO) > 0)
                .count();
        int weeklyNegative = (int) holdings.stream()
                .filter(h -> h.getWeeklyChange() != null && h.getWeeklyChange().compareTo(BigDecimal.ZERO) < 0)
                .count();
        int weeklyNeutral = (int) holdings.stream()
                .filter(h -> h.getWeeklyChange() == null || h.getWeeklyChange().compareTo(BigDecimal.ZERO) == 0)
                .count();

        // Monthly momentum counts
        int monthlyPositive = (int) holdings.stream()
                .filter(h -> h.getMonthlyChange() != null && h.getMonthlyChange().compareTo(BigDecimal.ZERO) > 0)
                .count();
        int monthlyNegative = (int) holdings.stream()
                .filter(h -> h.getMonthlyChange() != null && h.getMonthlyChange().compareTo(BigDecimal.ZERO) < 0)
                .count();
        int monthlyNeutral = (int) holdings.stream()
                .filter(h -> h.getMonthlyChange() == null || h.getMonthlyChange().compareTo(BigDecimal.ZERO) == 0)
                .count();

        // Top daily momentum stocks
        List<PortfolioAnalyticsDTO.MomentumStock> topDailyMomentum = holdings.stream()
                .filter(h -> h.getDailyChange() != null)
                .sorted(Comparator.comparing(HoldingDTO::getDailyChange).reversed())
                .limit(5)
                .map(this::toMomentumStock)
                .collect(Collectors.toList());

        // Worst daily momentum stocks
        List<PortfolioAnalyticsDTO.MomentumStock> worstDailyMomentum = holdings.stream()
                .filter(h -> h.getDailyChange() != null)
                .sorted(Comparator.comparing(HoldingDTO::getDailyChange))
                .limit(5)
                .map(this::toMomentumStock)
                .collect(Collectors.toList());

        // Top weekly momentum stocks
        List<PortfolioAnalyticsDTO.MomentumStock> topWeeklyMomentum = holdings.stream()
                .filter(h -> h.getWeeklyChange() != null)
                .sorted(Comparator.comparing(HoldingDTO::getWeeklyChange).reversed())
                .limit(5)
                .map(this::toMomentumStock)
                .collect(Collectors.toList());

        // Top monthly momentum stocks
        List<PortfolioAnalyticsDTO.MomentumStock> topMonthlyMomentum = holdings.stream()
                .filter(h -> h.getMonthlyChange() != null)
                .sorted(Comparator.comparing(HoldingDTO::getMonthlyChange).reversed())
                .limit(5)
                .map(this::toMomentumStock)
                .collect(Collectors.toList());

        return PortfolioAnalyticsDTO.MomentumAnalysis.builder()
                .dailyPositive(dailyPositive)
                .dailyNegative(dailyNegative)
                .dailyNeutral(dailyNeutral)
                .weeklyPositive(weeklyPositive)
                .weeklyNegative(weeklyNegative)
                .weeklyNeutral(weeklyNeutral)
                .monthlyPositive(monthlyPositive)
                .monthlyNegative(monthlyNegative)
                .monthlyNeutral(monthlyNeutral)
                .topDailyMomentum(topDailyMomentum)
                .topWeeklyMomentum(topWeeklyMomentum)
                .topMonthlyMomentum(topMonthlyMomentum)
                .worstDailyMomentum(worstDailyMomentum)
                .build();
    }

    private PortfolioAnalyticsDTO.MomentumStock toMomentumStock(HoldingDTO h) {
        return PortfolioAnalyticsDTO.MomentumStock.builder()
                .ticker(h.getTicker())
                .name(h.getName())
                .dailyChange(h.getDailyChange())
                .weeklyChange(h.getWeeklyChange())
                .monthlyChange(h.getMonthlyChange())
                .build();
    }
}
