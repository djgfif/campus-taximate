package com.campustaximate.matching;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 회차 한 번의 매칭(명세 4.4). DB와 시계에 의존하지 않는다.
 *
 * <ol>
 *   <li>풀(승차 지점·성별·시간 기준)마다 기준자별 조합을 만든다(1차 검사 통과, 이번 회차에 확정 가능).</li>
 *   <li>겹치지 않는 조합을 골라 (마지막 기회인 사람 수, 매칭 인원, 기다린 시간, 거리 절약, 요청 번호) 순으로 가장 좋게 한다.</li>
 *   <li>고른 조합만 2차 검사(카카오)를 한다. 떨어지면 빼고 다시 고른다. 기준자당 2차 검사는 최대 3번.</li>
 * </ol>
 * 조합 만들기와 고르기는 회차 시작부터 시간 한도(searchMillis) 안에서 찾은 가장 좋은 답이다.
 */
public final class MatchingRound {

    private final MatchingSettings settings;
    private final CandidateRanker ranker;
    private final Comparator<MatchRequest> anchorOrder;

    public MatchingRound(MatchingSettings settings) {
        this.settings = settings;
        this.ranker = new CandidateRanker(settings);
        this.anchorOrder = Comparator.<MatchRequest, Instant>comparing(r -> plan(r).urgency(r))
                .thenComparingLong(MatchRequest::id)
                .thenComparingLong(MatchRequest::anchorId);
    }

    private TripPlan plan(MatchRequest request) {
        return TripPlan.of(request.direction(), settings);
    }

    /** 조합 하나와 그 조합을 만든 기준자(2차 검사 횟수를 센다). */
    private static final class Option {
        final Candidate candidate;
        final long owner;
        final Set<Long> lastIds;
        final int lastChance;
        final long waited;
        final long savedMeters;
        boolean passed;
        boolean dead;
        Instant verifiedDeparture;

        Option(Candidate candidate, long owner, Set<Long> lastIds, long waited) {
            this.candidate = candidate;
            this.owner = owner;
            this.lastIds = lastIds;
            this.lastChance = lastIds.size();
            this.waited = waited;
            this.savedMeters = Math.round(candidate.savedKm() * 1000);
        }
    }

    /**
     * requests는 회차가 시작될 때 대기 중인 요청이다. 승차 지점을 여러 곳 고른 사람은 지점마다 하나씩 들어온다.
     * 엔진은 시계를 모르고 저장도 하지 않는다. 다음 회차까지 끝나지 않은 결과는 호출하는 스케줄러가 버린다.
     */
    public RoundResult run(Instant slot, List<MatchRequest> requests, LegProvider legs, GroupVerifier verifier) {
        requireAligned(slot);
        // 조합 만들기와 고르기를 합쳐 searchMillis 안에서 끝낸다. 넘으면 욕심껏 고른 답을 쓴다.
        long deadline = System.nanoTime() + Duration.ofMillis(settings.searchMillis()).toNanos();

        Map<MatchRequest.PoolKey, List<MatchRequest>> pools = new LinkedHashMap<>();
        requests.stream()
                .filter(r -> plan(r).eligible(r, slot))
                .sorted(anchorOrder)
                .forEach(r -> pools.computeIfAbsent(r.poolKey(), k -> new ArrayList<>()).add(r));

        // 같은 사람 묶음(요청 번호 집합)마다 승차 지점별 대안을 둔다. 걷기 합 → 거리 → 최대 지연 순(명세 4.2).
        Map<List<Long>, List<Option>> alternatives = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        generate:
        for (List<MatchRequest> pool : pools.values()) {
            for (MatchRequest anchor : pool) {
                if (System.nanoTime() > deadline) {
                    break generate;   // 시간 한도: 여기까지 만든 조합으로 고른다
                }
                for (Candidate c : ranker.rank(anchor, pool, slot, legs, deadline)) {
                    if (!seen.add(c.sortedIds() + "@" + c.anchorId())) {
                        continue;
                    }
                    alternatives.computeIfAbsent(c.sortedIds(), k -> new ArrayList<>())
                            .add(new Option(c, anchor.id(), lastChanceIds(c, slot), waited(c, slot)));
                }
            }
        }
        Comparator<Option> pointOrder = Comparator.<Option>comparingInt(o -> o.candidate.walkSum())
                .thenComparingDouble(o -> o.candidate.totalKm())
                .thenComparingInt(o -> o.candidate.maxDelay())
                .thenComparingLong(o -> o.candidate.anchorId());
        alternatives.values().forEach(list -> list.sort(pointOrder));

        Map<Long, Integer> checks = new HashMap<>();
        Set<Long> unavailableOwners = new HashSet<>();
        List<Option> chosen;
        while (true) {
            List<Option> alive = new ArrayList<>();
            for (List<Option> list : alternatives.values()) {
                for (Option o : list) {
                    if (o.dead) {
                        continue;
                    }
                    if (!o.passed && (unavailableOwners.contains(o.owner)
                            || checks.getOrDefault(o.owner, 0) >= settings.maxVerifications())) {
                        o.dead = true;
                        continue;
                    }
                    alive.add(o);
                    break;
                }
            }
            chosen = new Packer(alive, deadline).best();
            boolean allPassed = true;
            for (Option o : chosen) {
                if (o.passed) {
                    continue;
                }
                if (verifier.recentlyRejected(o.candidate)) {
                    o.dead = true;
                    allPassed = false;
                    continue;
                }
                if (unavailableOwners.contains(o.owner)
                        || checks.getOrDefault(o.owner, 0) >= settings.maxVerifications()) {
                    o.dead = true;
                    allPassed = false;
                    continue;
                }
                checks.merge(o.owner, 1, Integer::sum);
                Verification result = verifier.check(o.candidate);
                Verdict verdict = result.verdict();
                if (verdict == Verdict.PASS) {
                    o.passed = true;
                    o.verifiedDeparture = result.departure();
                    continue;
                }
                if (verdict == Verdict.UNAVAILABLE) {
                    unavailableOwners.add(o.owner);
                }
                o.dead = true;
                allPassed = false;
            }
            if (allPassed) {
                break;
            }
        }

        List<ConfirmedGroup> confirmed = new ArrayList<>();
        Set<Long> matched = new HashSet<>();
        chosen.stream()
                .sorted(Comparator.comparing((Option o) -> o.candidate.order().stream()
                        .map(r -> plan(r).urgency(r)).min(Instant::compareTo).orElseThrow())
                        .thenComparing(o -> o.candidate.sortedIds(), CandidateRanker.BY_IDS))
                .forEach(o -> {
                    List<MatchRequest> order = o.candidate.order();
                    TripPlan plan = plan(order.get(0));
                    Instant departure = o.verifiedDeparture != null ? o.verifiedDeparture
                            : o.candidate.departure() != null ? o.candidate.departure() : plan.departure(slot).orElse(null);
                    confirmed.add(new ConfirmedGroup(order, plan.payer(order), departure));
                    order.forEach(r -> matched.add(r.id()));
                });

        Set<Long> reported = new HashSet<>();
        List<MatchRequest> expired = requests.stream()
                .filter(r -> !matched.contains(r.id()))
                .filter(r -> plan(r).expired(r, slot))
                .sorted(anchorOrder)
                .filter(r -> reported.add(r.id()))
                .toList();
        return new RoundResult(confirmed, expired);
    }

    private Set<Long> lastChanceIds(Candidate c, Instant slot) {
        Set<Long> ids = new HashSet<>();
        c.order().stream().filter(r -> plan(r).lastChance(r, slot)).forEach(r -> ids.add(r.id()));
        return ids;
    }

    private static long waited(Candidate c, Instant slot) {
        return c.order().stream().filter(r -> r.requestedAt() != null)
                .mapToLong(r -> Math.max(0, slot.getEpochSecond() - r.requestedAt().getEpochSecond())).sum();
    }

    /**
     * 겹치지 않는 조합 고르기. 먼저 좋은 조합부터 욕심껏 담은 답을 만들고,
     * 시간 한도 안에서 사람 단위 깊이 우선 탐색(가지치기)으로 더 좋은 답을 찾는다.
     */
    private static final class Packer {
        private static final Comparator<Option> GOODNESS = Comparator.<Option>comparingInt(o -> -o.lastChance)
                .thenComparingInt(o -> -o.candidate.size())
                .thenComparingLong(o -> -o.waited)
                .thenComparingLong(o -> -o.savedMeters)
                .thenComparing(o -> o.candidate.sortedIds(), CandidateRanker.BY_IDS);

        private final List<Option> options;
        private final long deadline;
        /** 탐색 횟수 한도. 같은 입력이면 같은 답이 나오도록 시간보다 먼저 이 한도로 끊는다. */
        private static final long MAX_NODES = 100_000;
        private long nodes;
        private final List<Long> people;
        private final Map<Long, Integer> index = new HashMap<>();
        private final List<List<Option>> byFirst = new ArrayList<>();
        private final boolean[] last;
        private final boolean[] used;
        private final List<Option> current = new ArrayList<>();
        private List<Option> best = List.of();
        private long[] bestScore = {0, 0, 0, 0};

        Packer(List<Option> alive, long deadline) {
            this.options = new ArrayList<>(alive);
            this.options.sort(GOODNESS);
            this.deadline = deadline;
            Set<Long> ids = new java.util.TreeSet<>();
            options.forEach(o -> ids.addAll(o.candidate.sortedIds()));
            this.people = new ArrayList<>(ids);
            for (int i = 0; i < people.size(); i++) {
                index.put(people.get(i), i);
                byFirst.add(new ArrayList<>());
            }
            this.last = new boolean[people.size()];
            this.used = new boolean[people.size()];
            for (Option o : options) {
                byFirst.get(index.get(o.candidate.sortedIds().get(0))).add(o);
                o.lastIds.forEach(id -> last[index.get(id)] = true);
            }
        }

        List<Option> best() {
            greedy();
            search(0, new long[]{0, 0, 0, 0});
            return best;
        }

        private void greedy() {
            long[] score = {0, 0, 0, 0};
            for (Option o : options) {
                if (fits(o)) {
                    take(o, true);
                    score = add(score, o);
                }
            }
            best = List.copyOf(current);
            bestScore = score;
            for (Option o : new ArrayList<>(current)) {
                take(o, false);
            }
        }

        private void search(int k, long[] score) {
            int cmp = compare(score, bestScore);
            if (cmp > 0 || (cmp == 0 && !current.isEmpty() && idsBefore(current, best))) {
                best = List.copyOf(current);
                bestScore = score.clone();
            }
            while (k < people.size() && used[k]) {
                k++;
            }
            if (k >= people.size() || ++nodes > MAX_NODES || System.nanoTime() > deadline) {
                return;
            }
            long freeLast = 0;
            long free = 0;
            for (int i = k; i < people.size(); i++) {
                if (!used[i]) {
                    free++;
                    if (last[i]) {
                        freeLast++;
                    }
                }
            }
            if (score[0] + freeLast < bestScore[0]
                    || (score[0] + freeLast == bestScore[0] && score[1] + free < bestScore[1])) {
                return;
            }
            for (Option o : byFirst.get(k)) {
                if (fits(o)) {
                    take(o, true);
                    search(k + 1, add(score, o));
                    take(o, false);
                }
            }
            search(k + 1, score);
        }

        private boolean fits(Option o) {
            for (Long id : o.candidate.sortedIds()) {
                if (used[index.get(id)]) {
                    return false;
                }
            }
            return true;
        }

        private void take(Option o, boolean on) {
            for (Long id : o.candidate.sortedIds()) {
                used[index.get(id)] = on;
            }
            if (on) {
                current.add(o);
            } else {
                current.remove(o);
            }
        }

        private static long[] add(long[] score, Option o) {
            return new long[]{score[0] + o.lastChance, score[1] + o.candidate.size(),
                    score[2] + o.waited, score[3] + o.savedMeters};
        }

        private static int compare(long[] a, long[] b) {
            for (int i = 0; i < a.length; i++) {
                int c = Long.compare(a[i], b[i]);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        }

        private static boolean idsBefore(List<Option> a, List<Option> b) {
            return CandidateRanker.BY_IDS.compare(flat(a), flat(b)) < 0;
        }

        private static List<Long> flat(List<Option> options) {
            return options.stream().flatMap(o -> o.candidate.sortedIds().stream()).sorted().toList();
        }
    }

    private void requireAligned(Instant slot) {
        if (slot.getEpochSecond() % (settings.slotMinutes() * 60L) != 0 || slot.getNano() != 0) {
            throw new IllegalArgumentException("slot is not aligned: " + slot);
        }
    }
}
