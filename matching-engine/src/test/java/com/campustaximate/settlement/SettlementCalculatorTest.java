package com.campustaximate.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SettlementCalculatorTest {

    private static final List<Long> EXAMPLE = SettlementCalculator.ratioUnits(List.of(7000L, 11000L, 14000L));

    @Test
    void ratioUsesSoloFares() {
        assertEquals(List.of(7000L, 11000L, 14000L), EXAMPLE);
    }

    @Test
    void sharesFollowSoloFareExample() {
        assertEquals(List.of(3300L, 5200L, 6500L), SettlementCalculator.shares(15000, EXAMPLE, EXAMPLE.size() - 1));
    }

    @Test
    void ratioIsKeptWhenPaidAmountChanges() {
        assertEquals(List.of(3500L, 5500L, 7000L), SettlementCalculator.shares(16000, EXAMPLE, EXAMPLE.size() - 1));
    }

    @Test
    void ratioIsExactForFourRiders() {
        List<Long> units = SettlementCalculator.ratioUnits(List.of(5000L, 8000L, 11000L, 14000L));

        assertEquals(List.of(5000L, 8000L, 11000L, 14000L), units);
    }

    @Test
    void acceptsSoloFaresInAnyOrder() {
        assertEquals(List.of(10000L, 9000L),
                SettlementCalculator.ratioUnits(List.of(10000L, 9000L)));
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThrows(IllegalArgumentException.class, () -> SettlementCalculator.shares(0, EXAMPLE, EXAMPLE.size() - 1));
    }

    @Test
    void rejectsAmountThatMakesPayerShareNegative() {
        List<Long> units = SettlementCalculator.ratioUnits(List.of(5000L, 8000L, 11000L, 14000L));

        assertThrows(SettlementCalculator.NegativeShareException.class,
                () -> SettlementCalculator.shares(100, units, units.size() - 1));
    }

    @Test
    void rejectsInvalidWeightsAndAmountsAboveLimit() {
        assertThrows(IllegalArgumentException.class, () -> SettlementCalculator.ratioUnits(List.of(0L, 9000L)));
        assertThrows(IllegalArgumentException.class, () -> SettlementCalculator.ratioUnits(List.of(-1L, 9000L)));
        assertThrows(IllegalArgumentException.class, () -> SettlementCalculator.shares(10_000_001, EXAMPLE, EXAMPLE.size() - 1));
        assertEquals(10_000_000L, SettlementCalculator.shares(10_000_000, EXAMPLE, EXAMPLE.size() - 1).stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void sumEqualsPaidAmountForThousandRandomCases() {
        Random random = new Random(20261106);
        for (int i = 0; i < 1000; i++) {
            int riders = 2 + random.nextInt(3);
            List<Long> soloFares = new ArrayList<>();
            long fare = 4800;
            for (int k = 0; k < riders; k++) {
                fare = 4800 + 100L * random.nextInt(80);
                soloFares.add(fare);
            }
            long paid = fare + 100L * (random.nextInt(21) - 10);

            List<Long> shares = SettlementCalculator.shares(paid, SettlementCalculator.ratioUnits(soloFares), soloFares.size() - 1);

            assertEquals(paid, shares.stream().mapToLong(Long::longValue).sum());
            assertTrue(shares.stream().allMatch(s -> s >= 0));
        }
    }
}
