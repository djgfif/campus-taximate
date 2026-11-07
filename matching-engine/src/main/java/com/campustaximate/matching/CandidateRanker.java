package com.campustaximate.matching;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 기준자 한 명의 조합을 만들고 순위를 매긴다. */
public final class CandidateRanker {

    private static final Comparator<List<Long>> BY_IDS = (a, b) -> {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = Long.compare(a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    };

    private static final Comparator<Candidate> RANK = Comparator
            .comparingInt(Candidate::size).reversed()
            .thenComparingDouble(Candidate::totalKm)
            .thenComparing(Candidate::sortedIds, BY_IDS);

    private final MatchingSettings settings;
    private final EstimateCalculator calculator;

    public CandidateRanker(MatchingSettings settings) {
        this.settings = settings;
        this.calculator = new EstimateCalculator(settings);
    }

    /** others는 기준자와 같은 풀이고 이번 슬롯에 들어온 요청이다. */
    public List<Candidate> rank(MatchRequest anchor, List<MatchRequest> others, Instant slot, LegProvider legs) {
        List<MatchRequest> nearest = nearestByAngle(anchor, others);
        List<Candidate> result = new ArrayList<>();
        for (int size = settings.maxGroupSize(); size >= 2; size--) {
            collect(anchor, nearest, size - 1, 0, new ArrayList<>(), slot, legs, result);
        }
        result.sort(RANK);
        return result;
    }

    private List<MatchRequest> nearestByAngle(MatchRequest anchor, List<MatchRequest> others) {
        GeoPoint center = anchor.anchor();
        double anchorBearing = center.bearingTo(anchor.own());
        return others.stream()
                .sorted(Comparator.<MatchRequest>comparingDouble(
                                r -> angleGap(anchorBearing, center.bearingTo(r.own())))
                        .thenComparingLong(MatchRequest::id))
                .limit(settings.maxCandidates() - 1L)
                .toList();
    }

    private void collect(MatchRequest anchor, List<MatchRequest> pool, int need, int start,
                         List<MatchRequest> picked, Instant slot, LegProvider legs, List<Candidate> result) {
        if (need == 0) {
            List<MatchRequest> group = new ArrayList<>(picked);
            group.add(anchor);
            if (group.size() > 2 || TripPlan.of(anchor.direction(), settings).isDeadline(group, slot)) {
                calculator.evaluate(group, legs).ifPresent(result::add);
            }
            return;
        }
        for (int i = start; i <= pool.size() - need; i++) {
            picked.add(pool.get(i));
            collect(anchor, pool, need - 1, i + 1, picked, slot, legs, result);
            picked.remove(picked.size() - 1);
        }
    }

    private static double angleGap(double a, double b) {
        double gap = Math.abs(a - b) % (2 * Math.PI);
        return Math.min(gap, 2 * Math.PI - gap);
    }
}
