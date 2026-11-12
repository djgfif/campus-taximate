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
        // 3281.25 / 5156.25 / 6562.5 → 모두 버림 후 남는 1원은 결제자가 아닌 앞사람(소수부 같음)에게
        assertEquals(List.of(3282L, 5156L, 6562L), SettlementCalculator.shares(15000, EXAMPLE, EXAMPLE.size() - 1));
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
    void deckExampleGivesSameRateToEveryone() {
        List<Long> shares = SettlementCalculator.shares(16800, SettlementCalculator.ratioUnits(List.of(6000L, 10500L, 15000L)), 2);

        assertEquals(List.of(3200L, 5600L, 8000L), shares);
    }

    @Test
    void payerShareStaysNonNegativeForSmallAmount() {
        List<Long> units = SettlementCalculator.ratioUnits(List.of(5000L, 8000L, 11000L, 14000L));

        List<Long> shares = SettlementCalculator.shares(100, units, units.size() - 1);
        assertEquals(100L, shares.stream().mapToLong(Long::longValue).sum());
        assertTrue(shares.get(units.size() - 1) >= 0);
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

    /** P-03: 결제자 위치를 바꿔 가며 무작위 1,000건. 결제자 몫은 비례 금액(버림) 이하다. */
    @Test
    void payerNeverPaysMoreThanProportionalShareForThousandRandomCases() {
        Random random = new Random(20261110);
        for (int i = 0; i < 1000; i++) {
            int riders = 2 + random.nextInt(3);
            List<Long> soloFares = new ArrayList<>();
            for (int k = 0; k < riders; k++) {
                soloFares.add(4800 + 100L * random.nextInt(200) + random.nextInt(100));
            }
            long paid = 3000 + random.nextInt(60000);
            int payer = random.nextInt(riders);
            List<Long> units = SettlementCalculator.ratioUnits(soloFares);
            List<Long> shares = SettlementCalculator.shares(paid, units, payer);
            long total = units.stream().mapToLong(Long::longValue).sum();

            assertEquals(paid, shares.stream().mapToLong(Long::longValue).sum());
            assertTrue(shares.stream().allMatch(s -> s >= 0));
            assertTrue(shares.get(payer) <= paid * units.get(payer) / total);
        }
    }

    /** P-02: 10,001원 같은 비율, A 결제자 → 남는 2원은 B·C에게. */
    @Test
    void leftoverWonGoesToNonPayers() {
        List<Long> units = SettlementCalculator.ratioUnits(List.of(5000L, 5000L, 5000L));

        assertEquals(List.of(3333L, 3334L, 3334L), SettlementCalculator.shares(10001, units, 0));
    }

    /** 명세 6.3: 실제 24,000원 → A 4,572 / B 8,000 / C 11,428. */
    @Test
    void higherActualFareSplitsWithLeftoverToNonPayer() {
        List<Long> units = SettlementCalculator.ratioUnits(List.of(6000L, 10500L, 15000L));

        assertEquals(List.of(4572L, 8000L, 11428L), SettlementCalculator.shares(24000, units, 2));
    }

    /** N-01: B 노쇼, A·C 둘이 15,500원 → A 4,429 / C 11,071, 증가분 1,229 + 3,071 = 4,300. */
    @Test
    void noShowCompensationCoversIncreaseWithinCap() {
        List<Long> twoShares = SettlementCalculator.shares(15500, SettlementCalculator.ratioUnits(List.of(6000L, 15000L)), 1);
        assertEquals(List.of(4429L, 11071L), twoShares);

        List<Long> comp = SettlementCalculator.compensation(List.of(4429L - 3200L, 11071L - 8000L), 5600);
        assertEquals(List.of(1229L, 3071L), comp);
    }

    /** N-07: 증가분 합이 상한을 넘으면 상한을 증가분 비율로 나눈다. */
    @Test
    void compensationIsCappedAndSplitByIncrease() {
        List<Long> comp = SettlementCalculator.compensation(List.of(3200L, 9600L), 5600);

        assertEquals(5600L, comp.stream().mapToLong(Long::longValue).sum());
        assertEquals(List.of(1400L, 4200L), comp);
    }

    @Test
    void compensationIgnoresDecreases() {
        assertEquals(List.of(0L, 500L), SettlementCalculator.compensation(List.of(-300L, 500L), 5600));
        assertEquals(List.of(0L, 0L), SettlementCalculator.compensation(List.of(-300L, 0L), 5600));
    }
    @Test
    void N11_multipleAbsenteesPreserveBothTotalsAndCapsForThousandCases() {
        Random random = new Random(20261111);
        for (int n=0;n<1000;n++) {
            List<Long> caps=List.of((long)random.nextInt(20001),(long)random.nextInt(20001));
            List<Long> increases=List.of((long)random.nextInt(20001),(long)random.nextInt(20001));
            List<Long> credits=SettlementCalculator.compensation(increases,caps.get(0)+caps.get(1));
            long total=credits.stream().mapToLong(Long::longValue).sum();
            List<Long> charges=SettlementCalculator.compensationBurdens(total,caps,List.of(9L,2L));
            assertEquals(total,charges.stream().mapToLong(Long::longValue).sum());
            for(int i=0;i<2;i++) assertTrue(charges.get(i)>=0 && charges.get(i)<=caps.get(i));
        }
        assertEquals(List.of(0L,1L),SettlementCalculator.compensationBurdens(1,List.of(5L,5L),List.of(9L,2L)));
        assertThrows(IllegalArgumentException.class,()->SettlementCalculator.compensationBurdens(11,List.of(5L,5L),List.of(1L,2L)));
    }
}
