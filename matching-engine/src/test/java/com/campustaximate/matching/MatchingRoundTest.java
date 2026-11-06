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

    @Test
    void threePeopleConfirmImmediatelyWithPayerLastAndDepartureSlotPlusThirty() {
        var a = request(1, at(3, 0));
        var b = request(2, at(5, 0));
        var c = request(3, at(7, 0));

        RoundResult result = round.run(SLOT, List.of(c, a, b), LEGS, PASS);

        assertEquals(1, result.confirmed().size());
        ConfirmedGroup group = result.confirmed().get(0);
        assertEquals(List.of(a, b, c), group.dropoffOrder());
        assertEquals(c, group.payer());
        assertEquals(SLOT.plusSeconds(1800), group.departure());
    }

    @Test
    void twoPeopleWaitThenConfirmAtDeadline() {
        var a = request(1, at(3, 0), 30);
        var b = request(2, at(5, 0), 30);

        assertTrue(round.run(SLOT, List.of(a, b), LEGS, PASS).confirmed().isEmpty());
        assertEquals(1, round.run(SLOT.plusSeconds(1800), List.of(a, b), LEGS, PASS).confirmed().size());
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
    void requestOutsideWindowIsIgnoredAndNotExpired() {
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
    void anchorStopsAfterThreeChecks() {
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

        assertEquals(3, firstAnchorChecks.get());
        assertTrue(result.confirmed().stream().noneMatch(g -> g.dropoffOrder().contains(requests.get(0))));
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

        RoundResult result = round.run(SLOT, List.of(a, b, c, d), LEGS, skipFour);

        assertEquals(1, checks.get());
        assertEquals(3, result.confirmed().get(0).dropoffOrder().size());
    }

    @Test
    void nextCandidateIsConfirmedWhenFirstFails() {
        var a = request(1, at(3, 0));
        var b = request(2, at(5, 0));
        var c = request(3, at(7, 0));
        var d = request(4, at(9, 0));
        GroupVerifier rejectFour = cand -> cand.size() == 4 ? Verdict.FAIL : Verdict.PASS;

        RoundResult result = round.run(SLOT, List.of(a, b, c, d), LEGS, rejectFour);

        assertEquals(3, result.confirmed().get(0).dropoffOrder().size());
    }

    @Test
    void unavailableStopsTheAnchorAndDoesNotExpireBeforeDeadline() {
        var a = request(1, at(3, 0));
        var b = request(2, at(5, 0));
        var c = request(3, at(7, 0));
        var d = request(4, at(9, 0));
        AtomicInteger calls = new AtomicInteger();

        RoundResult result = round.run(SLOT, List.of(a, b, c, d), LEGS, cand -> {
            calls.incrementAndGet();
            return Verdict.UNAVAILABLE;
        });

        // 기준자 a와 b가 각각 첫 조합에서 멈춘다. 중단이 없으면 a만 3번 부른다.
        assertEquals(2, calls.get());
        assertTrue(result.confirmed().isEmpty());
        assertTrue(result.expired().isEmpty());
    }

    @Test
    void unmatchedRequestExpiresOnlyAtLastSlot() {
        var alone = request(1, at(3, 0), 5);

        assertTrue(round.run(SLOT, List.of(alone), LEGS, PASS).expired().isEmpty());
        assertEquals(List.of(alone), round.run(SLOT.plusSeconds(300), List.of(alone), LEGS, PASS).expired());
    }

    @Test
    void noRequestIsAssignedTwice() {
        RoundResult result = round.run(SLOT, randomRequests(20261106, 40), LEGS, PASS);

        Set<Long> seen = new HashSet<>();
        result.confirmed().forEach(g -> g.dropoffOrder().forEach(r -> assertTrue(seen.add(r.id()))));
        assertTrue(result.confirmed().size() >= 5);
        assertTrue(result.confirmed().stream().allMatch(g -> g.dropoffOrder().size() <= 4));
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
        var first = Fixtures.of(1, 101, HUB, Gender.FEMALE, SLOT, SLOT.plusSeconds(1800), at(3, 0));
        var second = Fixtures.of(2, 102, new Hub(2, HUB.location()), Gender.FEMALE,
                SLOT, SLOT.plusSeconds(1800), at(3, 0));

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
