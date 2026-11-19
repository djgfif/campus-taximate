package com.campustaximate.matching;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 기준자 한 명이 들어간 조합을 만들고 순위를 매긴다.
 * 방향이 비슷한 사람 11명까지만 보고(후보 12명), 이번 회차에 확정할 수 있는 조합만 남긴다.
 */
public final class CandidateRanker {

    static final Comparator<List<Long>> BY_IDS = (a, b) -> {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = Long.compare(a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    };

    /** 인원이 많은 순, 같으면 거리 절약이 큰 순, 같으면 요청 번호. */
    static final Comparator<Candidate> RANK = Comparator
            .comparingInt(Candidate::size).reversed()
            .thenComparing(Comparator.comparingDouble(Candidate::savedKm).reversed())
            .thenComparing(Candidate::sortedIds, BY_IDS)
            .thenComparingLong(Candidate::anchorId);

    private final MatchingSettings settings;
    private final EstimateCalculator calculator;

    public CandidateRanker(MatchingSettings settings) {
        this.settings = settings;
        this.calculator = new EstimateCalculator(settings);
    }

    /** others는 기준자와 같은 풀(같은 승차 지점·성별·시간 기준)이고 이번 회차에 들어온 요청이다. */
    public List<Candidate> rank(MatchRequest anchor, List<MatchRequest> others, Instant slot, LegProvider legs) {
        return rank(anchor, others, slot, legs, Long.MAX_VALUE);
    }

    /** deadlineNanos(System.nanoTime 기준)를 넘으면 그때까지 만든 조합만 돌려준다. */
    public List<Candidate> rank(MatchRequest anchor, List<MatchRequest> others, Instant slot, LegProvider legs,
                                long deadlineNanos) {
        List<MatchRequest> nearest = nearestByAngle(anchor, others);
        List<Candidate> result = new ArrayList<>();
        for (int size = settings.maxGroupSize(); size >= 2; size--) {
            collect(anchor, nearest, size - 1, 0, new ArrayList<>(), slot, legs, result, deadlineNanos);
        }
        result.sort(RANK);
        return result;
    }

    private List<MatchRequest> nearestByAngle(MatchRequest anchor, List<MatchRequest> others) {
        GeoPoint center = anchor.anchor();
        double anchorBearing = center.bearingTo(anchor.own());
        return others.stream()
                .filter(r -> r.id() != anchor.id() && r.memberId() != anchor.memberId() && !anchor.avoids(r))
                .sorted(Comparator.<MatchRequest>comparingDouble(
                                r -> angleGap(anchorBearing, center.bearingTo(r.own())))
                        .thenComparingLong(MatchRequest::id))
                .limit(settings.maxCandidates() - 1L)
                .toList();
    }

    private void collect(MatchRequest anchor, List<MatchRequest> pool, int need, int start,
                         List<MatchRequest> picked, Instant slot, LegProvider legs, List<Candidate> result,
                         long deadlineNanos) {
        if (System.nanoTime() > deadlineNanos) {
            return;   // 시간 한도
        }
        if (need == 0) {
            List<MatchRequest> group = new ArrayList<>(picked);
            group.add(anchor);
            if (TripPlan.of(anchor.direction(), settings).confirmable(group, slot, settings.maxGroupSize())) {
                calculator.evaluate(group, legs, slot).ifPresent(result::add);
            }
            return;
        }
        for (int i = start; i <= pool.size() - need; i++) {
            MatchRequest next = pool.get(i);
            // 서로 차단한 쌍, 같은 회원(요청이 둘로 들어온 경우)은 한 일행에 넣지 않는다.
            if (anchor.avoids(next) || picked.stream().anyMatch(r -> r.avoids(next) || r.memberId() == next.memberId())) {
                continue;
            }
            picked.add(next);
            collect(anchor, pool, need - 1, i + 1, picked, slot, legs, result, deadlineNanos);
            picked.remove(picked.size() - 1);
        }
    }

    private static double angleGap(double a, double b) {
        double gap = Math.abs(a - b) % (2 * Math.PI);
        return Math.min(gap, 2 * Math.PI - gap);
    }
}
