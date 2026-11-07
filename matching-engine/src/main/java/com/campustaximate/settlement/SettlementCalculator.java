package com.campustaximate.settlement;

import java.util.ArrayList;
import java.util.List;

/** 단독 요금 비율과 분담금을 계산한다. */
public final class SettlementCalculator {

    private SettlementCalculator() {
    }

    /** 확인 시점의 단독 요금을 비율의 가중치로 쓴다. */
    public static List<Long> ratioUnits(List<Long> soloFares) {
        if (soloFares.isEmpty() || soloFares.size() > 4
                || soloFares.stream().anyMatch(f -> f == null || f <= 0 || f > 10_000_000)) {
            throw new IllegalArgumentException("invalid solo fares");
        }
        return List.copyOf(soloFares);
    }

    /**
     * 결제 금액을 비율로 나눈다. payerIndex의 사람이 결제자이고 나머지를 부담한다.
     * 결제자 외는 원 단위로 나누고 1원 미만은 버린다. 남는 금액은 결제자가 내서 합계가 결제 금액과 같다.
     * 1원 미만을 버리므로 결제자 분담금은 음수가 되지 않는다.
     */
    public static List<Long> shares(long paidAmount, List<Long> ratioUnits, int payerIndex) {
        if (paidAmount <= 0 || paidAmount > 10_000_000) {
            throw new IllegalArgumentException("paidAmount must be positive");
        }
        ratioUnits = ratioUnits(ratioUnits);
        if (payerIndex < 0 || payerIndex >= ratioUnits.size()) {
            throw new IllegalArgumentException("invalid payer index");
        }
        long total = ratioUnits.stream().mapToLong(Long::longValue).sum();
        if (total <= 0) {
            throw new IllegalArgumentException("ratio total must be positive");
        }
        List<Long> shares = new ArrayList<>();
        long others = 0;
        for (int i = 0; i < ratioUnits.size(); i++) {
            if (i == payerIndex) {
                shares.add(0L);
                continue;
            }
            long share = Math.multiplyExact(paidAmount, ratioUnits.get(i)) / total;
            shares.add(share);
            others += share;
        }
        shares.set(payerIndex, paidAmount - others);
        return shares;
    }
}
