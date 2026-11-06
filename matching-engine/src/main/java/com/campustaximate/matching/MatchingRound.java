package com.campustaximate.matching;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 슬롯 한 번의 매칭를 실행한다. DB와 시계에 의존하지 않는다. */
public final class MatchingRound {

    private static final Comparator<MatchRequest> ANCHOR_ORDER = Comparator
            .comparing(MatchRequest::windowEnd).thenComparingLong(MatchRequest::id);

    private record PoolKey(long hubId, Gender gender) {
    }

    private final MatchingSettings settings;
    private final CandidateRanker ranker;

    public MatchingRound(MatchingSettings settings) {
        this.settings = settings;
        this.ranker = new CandidateRanker(settings);
    }

    /**
     * requests는 슬롯이 시작될 때 대기 중인 요청이다.
     * 엔진은 시계를 모르고 저장도 하지 않는다. 다음 슬롯까지 끝나지 않은 결과는 호출하는 스케줄러가 버린다.
     */
    public RoundResult run(Instant slot, List<MatchRequest> requests, LegProvider legs, GroupVerifier verifier) {
        requireAligned(slot);
        Instant departure = slot.plusSeconds(settings.leadMinutes() * 60L);

        Map<PoolKey, List<MatchRequest>> pools = new LinkedHashMap<>();
        requests.stream()
                .filter(r -> r.canDepartAt(departure))
                .sorted(ANCHOR_ORDER)
                .forEach(r -> pools.computeIfAbsent(new PoolKey(r.hub().id(), r.gender()),
                        k -> new ArrayList<>()).add(r));

        List<ConfirmedGroup> confirmed = new ArrayList<>();
        Set<Long> matched = new HashSet<>();
        for (List<MatchRequest> pool : pools.values()) {
            List<MatchRequest> remaining = new ArrayList<>(pool);
            while (remaining.size() >= 2) {
                MatchRequest anchor = remaining.remove(0);
                Optional<Candidate> found = firstVerified(anchor, remaining, slot, legs, verifier);
                if (found.isPresent()) {
                    List<MatchRequest> order = found.get().dropoffOrder();
                    confirmed.add(new ConfirmedGroup(order, departure));
                    order.forEach(r -> matched.add(r.id()));
                    remaining.removeAll(order);
                }
            }
        }

        List<MatchRequest> expired = requests.stream()
                .filter(r -> !matched.contains(r.id()))
                .filter(r -> !departure.isBefore(r.windowEnd()))
                .sorted(ANCHOR_ORDER)
                .toList();
        return new RoundResult(confirmed, expired);
    }

    private Optional<Candidate> firstVerified(MatchRequest anchor, List<MatchRequest> others,
                                              Instant slot, LegProvider legs, GroupVerifier verifier) {
        int checks = 0;
        for (Candidate candidate : ranker.rank(anchor, others, slot, legs)) {
            if (verifier.recentlyRejected(candidate)) {
                continue;
            }
            if (checks == settings.maxVerifications()) {
                break;
            }
            checks++;
            Verdict verdict = verifier.verify(candidate);
            if (verdict == Verdict.PASS) {
                return Optional.of(candidate);
            }
            if (verdict == Verdict.UNAVAILABLE) {
                break;
            }
        }
        return Optional.empty();
    }

    private void requireAligned(Instant slot) {
        if (slot.getEpochSecond() % (settings.slotMinutes() * 60L) != 0 || slot.getNano() != 0) {
            throw new IllegalArgumentException("slot is not aligned: " + slot);
        }
    }
}
