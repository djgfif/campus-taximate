package com.campustaximate.matching;

import static com.campustaximate.matching.Fixtures.HUB;
import static com.campustaximate.matching.Fixtures.LEGS;
import static com.campustaximate.matching.Fixtures.SLOT;
import static com.campustaximate.matching.Fixtures.at;
import static com.campustaximate.matching.Fixtures.bearing;
import static com.campustaximate.matching.Fixtures.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MatchingRoundTest {

    private static final GroupVerifier PASS = c -> Verdict.PASS;
    private static final GroupVerifier FAIL = c -> Verdict.FAIL;

    private final MatchingRound round = new MatchingRound(MatchingSettings.defaults());

    private static List<MatchRequest> randomRequests(long seed, int count) {
        Random random = new Random(seed);
        List<MatchRequest> requests = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            requests.add(request(i, bearing(random.nextDouble(80), 3 + random.nextDouble(12))));
        }
        return requests;
    }

    /** 기본 요청의 모집 마감 회차. 출발 가능 끝(SLOT+60분) − P(25분). */
    private static final Instant DEADLINE = SLOT.plusSeconds(2100);

    @Test
    void fourPeopleConfirmImmediatelyWithPayerLastAndDepartureAfterLead() {
        var a = request(1, at(3, 0));
        var b = request(2, at(5, 0));
        var c = request(3, at(7, 0));
        var d = request(4, at(9, 0));

        RoundResult result = round.run(SLOT, List.of(d, c, a, b), LEGS, PASS);

        assertEquals(1, result.confirmed().size());
        ConfirmedGroup group = result.confirmed().get(0);
        assertEquals(List.of(a, b, c, d), group.order());
        assertEquals(d, group.payer());
        // 확정 + P(25분) 이후 가장 이른 5분 단위 시각
        assertEquals(SLOT.plusSeconds(1500), group.departure());
    }

    @Test
    void threePeopleWaitForFourthThenConfirmAtDeadline() {
        var a = request(1, at(3, 0));
        var b = request(2, at(5, 0));
        var c = request(3, at(7, 0));

        assertTrue(round.run(SLOT, List.of(c, a, b), LEGS, PASS).confirmed().isEmpty());
        RoundResult result = round.run(DEADLINE, List.of(c, a, b), LEGS, PASS);

        assertEquals(1, result.confirmed().size());
        ConfirmedGroup group = result.confirmed().get(0);
        assertEquals(List.of(a, b, c), group.order());
        assertEquals(c, group.payer());
        assertEquals(DEADLINE.plusSeconds(1500), group.departure());
    }

    @Test
    void twoPeopleWaitThenConfirmAtDeadline() {
        var a = request(1, at(3, 0), 30);
        var b = request(2, at(5, 0), 30);

        assertTrue(round.run(SLOT, List.of(a, b), LEGS, PASS).confirmed().isEmpty());
        assertEquals(1, round.run(DEADLINE, List.of(a, b), LEGS, PASS).confirmed().size());
    }

    @Test
    void avoidedPairIsNeverGroupedTogether() {
        var a = request(1, at(3, 0));
        var b = request(2, at(5, 0));
        var c = request(3, at(7, 0));
        var d = request(4, at(9, 0));
        // A를 기준자로 삼을 때뿐 아니라 다른 기준자의 조합 안에서도 B/C를 거른다.
        for (boolean reverse : List.of(false, true)) {
            var second = reverse ? b : b.withAvoidMemberIds(Set.of(c.memberId()));
            var third = reverse ? c.withAvoidMemberIds(Set.of(b.memberId())) : c;
            RoundResult result = round.run(DEADLINE, List.of(a, second, third, d), LEGS, candidate -> {
                Set<Long> ids = new HashSet<>(candidate.order().stream().map(MatchRequest::memberId).toList());
                assertTrue(!(ids.contains(b.memberId()) && ids.contains(c.memberId())));
                return Verdict.PASS;
            });
            // 매칭 인원을 최대로 하므로 B와 C를 갈라 4명 모두 태운다(명세 4.4).
            assertEquals(4, result.confirmed().stream().mapToInt(g -> g.order().size()).sum());
            for (ConfirmedGroup g : result.confirmed()) {
                Set<Long> members = new HashSet<>(g.order().stream().map(MatchRequest::memberId).toList());
                assertTrue(!(members.contains(b.memberId()) && members.contains(c.memberId())));
            }
        }
        // 기준자와의 직접 회피도 제외한다. 두 명만 남은 회피 쌍은 마감에도 성사되지 않는다.
        var blocked = a.withAvoidMemberIds(Set.of(b.memberId()));
        assertTrue(round.run(DEADLINE, List.of(blocked, b), LEGS, PASS).confirmed().isEmpty());
        RoundResult four = round.run(DEADLINE, List.of(blocked, b, c, d), LEGS, PASS);
        assertEquals(4, four.confirmed().stream().mapToInt(g -> g.order().size()).sum());
        assertTrue(four.confirmed().stream().noneMatch(g -> g.order().contains(blocked) && g.order().contains(b)));
    }

    @Test
    void differentGenderOrHubAreNotGrouped() {
        var a = request(1, at(3, 0));
        var b = request(2, at(5, 0));
        var otherGender = Fixtures.of(3, 103, HUB, Gender.MALE, a.windowStart(), a.windowEnd(), at(7, 0));
        var otherHub = Fixtures.of(4, 104, new Hub(2, HUB.location()), Gender.FEMALE,
                a.windowStart(), a.windowEnd(), at(7, 0));

        assertTrue(round.run(SLOT, List.of(a, b, otherGender, otherHub), LEGS, PASS).confirmed().isEmpty());
    }

    @Test
    void laterWindowWaitsInPoolAndDoesNotExpire() {
        var a = request(1, at(3, 0));
        var b = request(2, at(5, 0));
        Instant departure = SLOT.plusSeconds(1800);
        var later = Fixtures.of(3, 103, HUB, Gender.FEMALE, departure.plusSeconds(300),
                departure.plusSeconds(3600), at(7, 0));

        RoundResult result = round.run(SLOT, List.of(a, b, later), LEGS, PASS);

        assertTrue(result.confirmed().isEmpty());
        assertTrue(result.expired().isEmpty());
    }

    @Test
    void anchorUsesAtMostThreeChecks() {
        List<MatchRequest> requests = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            requests.add(request(i, at(2 + i, 0)));
        }
        AtomicInteger firstAnchorChecks = new AtomicInteger();
        GroupVerifier failFirstAnchor = c -> {
            if (c.sortedIds().contains(1L)) {
                firstAnchorChecks.incrementAndGet();
                return Verdict.FAIL;
            }
            return Verdict.PASS;
        };

        RoundResult result = round.run(SLOT, requests, LEGS, failFirstAnchor);

        assertTrue(firstAnchorChecks.get() <= 3);
        assertTrue(result.confirmed().stream().noneMatch(g -> g.order().contains(requests.get(0))));
    }

    @Test
    void recentlyRejectedCandidateDoesNotUseUpChecks() {
        var a = request(1, at(3, 0));
        var b = request(2, at(5, 0));
        var c = request(3, at(7, 0));
        var d = request(4, at(9, 0));
        AtomicInteger checks = new AtomicInteger();
        GroupVerifier skipFour = new GroupVerifier() {
            @Override
            public Verdict verify(Candidate candidate) {
                checks.incrementAndGet();
                return Verdict.PASS;
            }

            @Override
            public boolean recentlyRejected(Candidate candidate) {
                return candidate.size() == 4;
            }
        };

        RoundResult result = round.run(DEADLINE, List.of(a, b, c, d), LEGS, skipFour);

        // 4명 조합은 건너뛰고, 남은 것 중 인원이 최대인 두 쌍(4명)을 확인한다.
        assertEquals(2, checks.get());
        assertEquals(4, result.confirmed().stream().mapToInt(g -> g.order().size()).sum());
    }

    @Test
    void nextCandidateIsConfirmedWhenFirstFails() {
        var a = request(1, at(3, 0));
        var b = request(2, at(5, 0));
        var c = request(3, at(7, 0));
        var d = request(4, at(9, 0));
        GroupVerifier rejectFour = cand -> cand.size() == 4 ? Verdict.FAIL : Verdict.PASS;

        RoundResult result = round.run(DEADLINE, List.of(a, b, c, d), LEGS, rejectFour);

        // 4명이 떨어지면 다시 골라 두 쌍으로 4명을 모두 태운다.
        assertEquals(2, result.confirmed().size());
        assertTrue(result.confirmed().stream().allMatch(g -> g.order().size() == 2));
    }

    @Test
    void unavailableStopsTheAnchorAndDoesNotExpireBeforeDeadline() {
        var a = request(1, at(3, 0));
        var b = request(2, at(5, 0));
        var c = request(3, at(7, 0));
        var d = request(4, at(9, 0));
        var e = request(5, at(11, 0));
        AtomicInteger calls = new AtomicInteger();

        RoundResult result = round.run(SLOT, List.of(a, b, c, d, e), LEGS, cand -> {
            calls.incrementAndGet();
            return Verdict.UNAVAILABLE;
        });

        // 통신 실패가 나면 그 조합을 만든 기준자는 이번 회차에 더 묻지 않는다. 4명 조합은 a 것과 b 것만 남는다.
        assertEquals(2, calls.get());
        assertTrue(result.confirmed().isEmpty());
        assertTrue(result.expired().isEmpty());
    }

    @Test
    void unmatchedRequestExpiresOnlyAtLastSlot() {
        var alone = request(1, at(3, 0), 5); // 끝 SLOT+35분 → 마감 SLOT+10분

        assertTrue(round.run(SLOT.plusSeconds(300), List.of(alone), LEGS, PASS).expired().isEmpty());
        assertEquals(List.of(alone), round.run(SLOT.plusSeconds(600), List.of(alone), LEGS, PASS).expired());
    }

    @Test
    void noRequestIsAssignedTwice() {
        RoundResult result = round.run(SLOT, randomRequests(20261106, 40), LEGS, PASS);

        Set<Long> seen = new HashSet<>();
        result.confirmed().forEach(g -> g.order().forEach(r -> assertTrue(seen.add(r.id()))));
        assertTrue(result.confirmed().size() >= 5);
        assertTrue(result.confirmed().stream().allMatch(g -> g.order().size() <= 4));
    }

    @Test
    void inputOrderDoesNotChangeTheResult() {
        List<MatchRequest> requests = randomRequests(7, 30);
        List<MatchRequest> shuffled = new ArrayList<>(requests);
        Collections.shuffle(shuffled, new Random(1));

        assertEquals(round.run(SLOT, requests, LEGS, PASS), round.run(SLOT, shuffled, LEGS, PASS));
    }

    @Test
    void expiredOrderDoesNotDependOnInputOrder() {
        var first = Fixtures.of(1, 101, HUB, Gender.FEMALE, SLOT, SLOT.plusSeconds(1500), at(3, 0));
        var second = Fixtures.of(2, 102, new Hub(2, HUB.location()), Gender.FEMALE,
                SLOT, SLOT.plusSeconds(1500), at(3, 0));

        assertEquals(List.of(first, second), round.run(SLOT, List.of(second, first), LEGS, PASS).expired());
    }

    @Test
    void hundredRequestsFinishWithinTwoSeconds() {
        List<MatchRequest> requests = randomRequests(100, 100);

        assertTimeout(Duration.ofSeconds(2), () -> round.run(SLOT, requests, LEGS, FAIL));
    }

    @Test
    void rejectsUnalignedSlot() {
        assertThrows(IllegalArgumentException.class,
                () -> round.run(SLOT.plusSeconds(60), List.of(), LEGS, PASS));
    }
}
