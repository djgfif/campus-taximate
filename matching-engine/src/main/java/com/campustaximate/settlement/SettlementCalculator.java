package com.campustaximate.settlement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** 단독 요금 비율과 분담금을 계산한다. 명세 6.2, 8.4. */
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
     * 결제 금액을 비율로 나눈다(명세 6.2). 모두 1원 미만을 버리고, 남는 원은 결제자를 뺀 사람에게
     * 소수 부분이 큰 순서로 1원씩 준다. 소수 부분이 같으면 앞 순서(하차 순서)부터 준다.
     * 결제자는 나머지를 내므로 자기 비례 금액보다 더 내지 않는다.
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
        int n = ratioUnits.size();
        long[] remainder = new long[n];
        List<Long> shares = new ArrayList<>();
        long floorSum = 0;
        for (int i = 0; i < n; i++) {
            long scaled = Math.multiplyExact(paidAmount, ratioUnits.get(i));
            shares.add(scaled / total);
            remainder[i] = scaled % total;
            floorSum += scaled / total;
        }
        long left = paidAmount - floorSum;
        List<Integer> order = IntStream.range(0, n).boxed()
                .filter(i -> i != payerIndex)
                .sorted(Comparator.<Integer>comparingLong(i -> remainder[i]).reversed().thenComparing(i -> i))
                .toList();
        for (int k = 0; k < order.size() && left > 0; k++) {
            int i = order.get(k);
            shares.set(i, shares.get(i) + 1);
            left--;
        }
        long others = 0;
        for (int i = 0; i < n; i++) {
            if (i != payerIndex) {
                others += shares.get(i);
            }
        }
        shares.set(payerIndex, paidAmount - others);
        return shares;
    }

    /**
     * 노쇼·직전 취소 보전(명세 8.4). increases는 남은 사람별 증가분(실제 몫 - 확정 때 예상 분담금),
     * cap은 빠진 사람의 확정 때 예상 분담금이다. 보전 총액 = min(양수 증가분 합, cap)을 증가분 비율로 나눈다.
     * 1원 미만은 버리고 남는 원은 소수 부분이 큰 순서(같으면 앞 순서)로 준다.
     */
    public static List<Long> compensation(List<Long> increases, long cap) {
        if (cap < 0 || increases.isEmpty()) {
            throw new IllegalArgumentException("invalid compensation input");
        }
        List<Long> positive = increases.stream().map(v -> v == null ? 0L : Math.max(0L, v)).toList();
        long sum = positive.stream().mapToLong(Long::longValue).sum();
        long total = Math.min(sum, cap);
        int n = positive.size();
        List<Long> result = new ArrayList<>();
        if (total == 0) {
            for (int i = 0; i < n; i++) {
                result.add(0L);
            }
            return result;
        }
        long[] rem = new long[n];
        long given = 0;
        for (int i = 0; i < n; i++) {
            long scaled = Math.multiplyExact(total, positive.get(i));
            result.add(scaled / sum);
            rem[i] = scaled % sum;
            given += scaled / sum;
        }
        long left = total - given;
        List<Integer> order = IntStream.range(0, n).boxed()
                .filter(i -> positive.get(i) > 0)
                .sorted(Comparator.<Integer>comparingLong(i -> rem[i]).reversed().thenComparing(i -> i))
                .toList();
        for (int k = 0; k < order.size() && left > 0; k++) {
            int i = order.get(k);
            result.set(i, result.get(i) + 1);
            left--;
        }
        return result;
    }
    /** 여러 이탈자의 상한 비율로 부담을 나눈다. 소수부 동률이면 요청 번호가 빠른 순서다. */
    public static List<Long> compensationBurdens(long total, List<Long> caps, List<Long> requestIds) {
        if (caps == null || caps.isEmpty() || caps.size() > 4 || requestIds == null || caps.size() != requestIds.size()
                || new java.util.HashSet<>(requestIds).size() != requestIds.size()
                || requestIds.stream().anyMatch(id -> id == null || id <= 0)
                || caps.stream().anyMatch(c -> c == null || c < 0 || c > 10_000_000L))
            throw new IllegalArgumentException("보전 상한과 요청 번호를 확인해 주세요.");
        long sum = caps.stream().mapToLong(Long::longValue).sum();
        if (total < 0 || total > sum) throw new IllegalArgumentException("보전 총액은 상한 합 이하여야 합니다.");
        List<Integer> order = java.util.stream.IntStream.range(0, caps.size()).boxed()
                .sorted(java.util.Comparator.comparing(requestIds::get)).toList();
        List<Long> sorted = compensation(order.stream().map(caps::get).toList(), total);
        List<Long> result = new java.util.ArrayList<>(java.util.Collections.nCopies(caps.size(), 0L));
        for (int i = 0; i < order.size(); i++) result.set(order.get(i), sorted.get(i));
        return List.copyOf(result);
    }
}
