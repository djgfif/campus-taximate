package com.campustaximate.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SettlementCalculatorTest {

    private static final List<Long> EXAMPLE = SettlementCalculator.ratioUnits(List.of(6000L, 10500L, 15000L));

    @Test
    void ratioFollowsTheDeckExample() {
        assertEquals(List.of(2000L * 12, 4250L * 12, 8750L * 12), EXAMPLE);
    }

    @Test
    void sharesFollowTheDeckExample() {
        assertEquals(List.of(2000L, 4300L, 8700L), SettlementCalculator.shares(15000, EXAMPLE));
    }

    @Test
    void ratioIsKeptWhenPaidAmountChanges() {
        assertEquals(List.of(2200L, 4600L, 9200L), SettlementCalculator.shares(16000, EXAMPLE));
    }

    @Test
    void ratioIsExactForFourRiders() {
        List<Long> units = SettlementCalculator.ratioUnits(List.of(5000L, 8000L, 11000L, 14000L));

        assertEquals(List.of(1250L * 12, 2250L * 12, 3750L * 12, 6750L * 12), units);
    }

    @Test
    void rejectsDecreasingCumulativeFare() {
        assertThrows(IllegalArgumentException.class,
                () -> SettlementCalculator.ratioUnits(List.of(10000L, 9000L)));
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThrows(IllegalArgumentException.class, () -> SettlementCalculator.shares(0, EXAMPLE));
    }

    @Test
    void rejectsAmountThatMakesPayerShareNegative() {
        List<Long> units = SettlementCalculator.ratioUnits(List.of(5000L, 8000L, 11000L, 14000L));

        assertThrows(SettlementCalculator.NegativeShareException.class,
                () -> SettlementCalculator.shares(100, units));
    }

    @Test
    void sumEqualsPaidAmountForThousandRandomCases() {
        Random random = new Random(20261106);
        for (int i = 0; i < 1000; i++) {
            int riders = 2 + random.nextInt(3);
            List<Long> cumulative = new ArrayList<>();
            long fare = 4800;
            for (int k = 0; k < riders; k++) {
                fare += 100L * random.nextInt(80);
                cumulative.add(fare);
            }
            long paid = fare + 100L * (random.nextInt(21) - 10);

            List<Long> shares = SettlementCalculator.shares(paid, SettlementCalculator.ratioUnits(cumulative));

            assertEquals(paid, shares.stream().mapToLong(Long::longValue).sum());
            assertTrue(shares.stream().allMatch(s -> s >= 0));
        }
    }
}
