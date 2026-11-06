package com.campustaximate.settlement;

import java.util.ArrayList;
import java.util.List;

/** 구간 비율과 몫을 계산하는 순수 함수 모음. 금액 입력 방식을 모른다. */
public final class SettlementCalculator {

    /** 1~4명의 공배수. 비율을 정수로 저장하려고 금액에 곱한다. */
    public static final long UNITS_PER_WON = 12;

    private static final long ROUND_UNIT = 100;

    private SettlementCalculator() {
    }

    /**
     * 하차 순서대로의 누적 요금에서 사람별 비율을 구한다.
     * 구간 요금을 그 구간을 탄 사람 수로 나눠 더한 값이고, 단위는 원의 12분의 1이다.
     */
    public static List<Long> ratioUnits(List<Long> cumulativeFares) {
        int riders = cumulativeFares.size();
        if (riders < 1 || riders > UNITS_PER_WON) {
            throw new IllegalArgumentException("riders out of range: " + riders);
        }
        List<Long> units = new ArrayList<>();
        long previous = 0;
        long accumulated = 0;
        for (int i = 0; i < riders; i++) {
            long segment = cumulativeFares.get(i) - previous;
            if (segment < 0) {
                throw new IllegalArgumentException("cumulative fare decreased at " + i);
            }
            accumulated += segment * UNITS_PER_WON / (riders - i);
            units.add(accumulated);
            previous = cumulativeFares.get(i);
        }
        return units;
    }

    /**
     * 결제 금액을 비율로 나눈다. 마지막 사람이 결제자이고 나머지를 부담한다.
     * 결제자 외는 100원 단위로 올린다.
     *
     * @throws NegativeShareException 결제자 몫이 음수가 될 때
     */
    public static List<Long> shares(long paidAmount, List<Long> ratioUnits) {
        if (paidAmount <= 0) {
            throw new IllegalArgumentException("paidAmount must be positive");
        }
        long total = ratioUnits.stream().mapToLong(Long::longValue).sum();
        if (total <= 0) {
            throw new IllegalArgumentException("ratio total must be positive");
        }
        List<Long> shares = new ArrayList<>();
        long others = 0;
        for (int i = 0; i < ratioUnits.size() - 1; i++) {
            long numerator = Math.multiplyExact(paidAmount, ratioUnits.get(i));
            long denominator = Math.multiplyExact(total, ROUND_UNIT);
            long share = (numerator + denominator - 1) / denominator * ROUND_UNIT;
            shares.add(share);
            others += share;
        }
        long payer = paidAmount - others;
        if (payer < 0) {
            throw new NegativeShareException();
        }
        shares.add(payer);
        return shares;
    }

    public static final class NegativeShareException extends RuntimeException {
        public NegativeShareException() {
            super("payer share is negative");
        }
    }
}
