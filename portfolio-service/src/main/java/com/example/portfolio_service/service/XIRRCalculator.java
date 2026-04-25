package com.example.portfolio_service.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * XIRR (Extended Internal Rate of Return) Calculator
 *
 * XIRR calculates the annualized return considering the timing and amount of all cash flows.
 * - Positive cash flows = money coming IN (sells, dividends, current value)
 * - Negative cash flows = money going OUT (buys)
 */
public class XIRRCalculator {

    public static class CashFlow {
        public final LocalDate date;
        public final double amount; // negative for outflows (buys), positive for inflows (sells)

        public CashFlow(LocalDate date, double amount) {
            this.date = date;
            this.amount = amount;
        }

        public CashFlow(LocalDate date, BigDecimal amount) {
            this.date = date;
            this.amount = amount.doubleValue();
        }
    }

    /**
     * Calculate XIRR for a list of cash flows
     *
     * @param cashFlows List of cash flows (dates and amounts)
     * @return Annualized return as decimal (e.g., 0.15 for 15%)
     * @throws IllegalArgumentException if XIRR cannot be calculated
     */
    public static double calculateXIRR(List<CashFlow> cashFlows) {
        if (cashFlows == null || cashFlows.size() < 2) {
            throw new IllegalArgumentException("Need at least 2 cash flows to calculate XIRR");
        }

        // Check if there are both positive and negative cash flows
        boolean hasPositive = false;
        boolean hasNegative = false;
        for (CashFlow cf : cashFlows) {
            if (cf.amount > 0) hasPositive = true;
            if (cf.amount < 0) hasNegative = true;
        }
        if (!hasPositive || !hasNegative) {
            throw new IllegalArgumentException("Need both positive and negative cash flows");
        }

        // Use Newton-Raphson method to find XIRR
        double guess = 0.1; // Start with 10% guess
        double rate = guess;

        final int MAX_ITERATIONS = 100;
        final double TOLERANCE = 1e-7;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double npv = calculateNPV(cashFlows, rate);
            double derivative = calculateNPVDerivative(cashFlows, rate);

            if (Math.abs(derivative) < TOLERANCE) {
                // Try a different starting point
                rate = rate + 0.1;
                continue;
            }

            double newRate = rate - npv / derivative;

            if (Math.abs(newRate - rate) < TOLERANCE) {
                return newRate;
            }

            rate = newRate;

            // Bound the rate to reasonable values
            if (rate < -0.99) rate = -0.99;
            if (rate > 10) rate = 10; // 1000% max
        }

        // If Newton-Raphson didn't converge, try bisection method
        return bisectionXIRR(cashFlows, -0.99, 5.0);
    }

    /**
     * Calculate NPV (Net Present Value) for a given rate
     */
    private static double calculateNPV(List<CashFlow> cashFlows, double rate) {
        LocalDate firstDate = cashFlows.get(0).date;
        double npv = 0;

        for (CashFlow cf : cashFlows) {
            long days = ChronoUnit.DAYS.between(firstDate, cf.date);
            double years = days / 365.0;
            npv += cf.amount / Math.pow(1 + rate, years);
        }

        return npv;
    }

    /**
     * Calculate the derivative of NPV with respect to rate (for Newton-Raphson)
     */
    private static double calculateNPVDerivative(List<CashFlow> cashFlows, double rate) {
        LocalDate firstDate = cashFlows.get(0).date;
        double derivative = 0;

        for (CashFlow cf : cashFlows) {
            long days = ChronoUnit.DAYS.between(firstDate, cf.date);
            double years = days / 365.0;
            derivative -= years * cf.amount / Math.pow(1 + rate, years + 1);
        }

        return derivative;
    }

    /**
     * Bisection method as fallback if Newton-Raphson doesn't converge
     */
    private static double bisectionXIRR(List<CashFlow> cashFlows, double low, double high) {
        final int MAX_ITERATIONS = 100;
        final double TOLERANCE = 1e-7;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double mid = (low + high) / 2;
            double npv = calculateNPV(cashFlows, mid);

            if (Math.abs(npv) < TOLERANCE || (high - low) / 2 < TOLERANCE) {
                return mid;
            }

            if (npv * calculateNPV(cashFlows, low) < 0) {
                high = mid;
            } else {
                low = mid;
            }
        }

        return (low + high) / 2;
    }

    /**
     * Calculate XIRR and return as percentage
     */
    public static double calculateXIRRPercentage(List<CashFlow> cashFlows) {
        return calculateXIRR(cashFlows) * 100;
    }

    /**
     * Safely calculate XIRR, returning null if calculation fails
     */
    public static Double safeCalculateXIRR(List<CashFlow> cashFlows) {
        try {
            return calculateXIRR(cashFlows);
        } catch (Exception e) {
            return null;
        }
    }
}
