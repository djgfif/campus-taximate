package com.campustaximate.matching;

import static com.campustaximate.matching.Fixtures.HUB;
import static com.campustaximate.matching.Fixtures.LEGS;
import static com.campustaximate.matching.Fixtures.SLOT;
import static com.campustaximate.matching.Fixtures.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 3차 명세 12장 수용 테스트 중 엔진이 맡는 것(M-06~M-11, T-01, T-02). */
class SpecAcceptanceTest {

    private static final GroupVerifier PASS = c -> Verdict.PASS;
    private final MatchingRound round = new MatchingRound(MatchingSettings.defaults());
    private final MatchingSettings settings = MatchingSettings.defaults();

    /** SLOT+20분~SLOT+60분에 출발 가능, requestedAt에 요청한 예약. */
    private static MatchRequest reserved(long id, Hub point, double east, Instant requestedAt) {
        return Fixtures.of(id, id + 100, point, Gender.FEMALE, SLOT.plusSeconds(1200), SLOT.plusSeconds(3600),
                at(east, 0)).requested(requestedAt, false);
    }

    private static Set<Long> ids(ConfirmedGroup g) {
        return Set.copyOf(g.order().stream().map(MatchRequest::id).toList());
    }

    /** M-06: A 정문·후문, B 후문·역, C 정문·후문 → 후문에서 3명. */
    @Test
    void m06PickupPointIntersection() {
        Hub front = new Hub(1, at(0, 0));
        Hub back = new Hub(2, at(0.1, 0));
        Hub station = new Hub(3, at(0, 0.1));
        Instant asked = SLOT.minusSeconds(900);
        List<MatchRequest> requests = List.of(
                reserved(1, front, 3, asked), reserved(1, back, 3, asked),
                reserved(2, back, 4, asked), reserved(2, station, 4, asked),
                reserved(3, front, 5, asked), reserved(3, back, 5, asked));

        RoundResult result = round.run(SLOT, requests, LEGS, PASS);

        assertEquals(1, result.confirmed().size());
        ConfirmedGroup g = result.confirmed().get(0);
        assertEquals(Set.of(1L, 2L, 3L), ids(g));
        assertTrue(g.order().stream().allMatch(r -> r.anchorId() == 2));
    }

    /** M-07: 공통 승차 지점이 없으면 후보가 아니다. */
    @Test
    void m07NoCommonPoint() {
        Instant asked = SLOT.minusSeconds(900);
        var a = reserved(1, new Hub(1, at(0, 0)), 3, asked);
        var b = reserved(2, new Hub(3, at(0, 0.1)), 4, asked);

        assertTrue(round.run(SLOT, List.of(a, b), LEGS, PASS).confirmed().isEmpty());
    }

    private static final Set<Set<Long>> ALLOWED = Set.of(Set.of(1L, 2L, 3L, 4L), Set.of(1L, 2L, 5L), Set.of(3L, 4L, 6L));

    /** ABCD, ABE, CDF(와 그 부분집합)만 1차를 통과하는 구간 값. */
    private static final LegProvider ONLY_ALLOWED = (x, y) -> ALLOWED.stream()
            .anyMatch(s -> s.contains(x.id()) && s.contains(y.id())) ? LEGS.leg(x, y) : Optional.empty();

    private static final GroupVerifier ONLY_THREE = c -> ALLOWED.contains(Set.copyOf(c.sortedIds()))
            ? Verdict.PASS : Verdict.FAIL;

    /** M-08: 모두 확정 자격(10분 지남), ABCD·ABE·CDF만 통과 → ABE + CDF 6명. */
    @Test
    void m08MaximizesMatchedRiders() {
        Instant asked = SLOT.minusSeconds(900);
        List<MatchRequest> requests = List.of(
                reserved(1, HUB, 3, asked), reserved(2, HUB, 3.5, asked), reserved(3, HUB, 4, asked),
                reserved(4, HUB, 4.5, asked), reserved(5, HUB, 5, asked), reserved(6, HUB, 5.5, asked));

        RoundResult result = round.run(SLOT, requests, ONLY_ALLOWED, ONLY_THREE);

        assertEquals(Set.of(Set.of(1L, 2L, 5L), Set.of(3L, 4L, 6L)),
                Set.copyOf(result.confirmed().stream().map(SpecAcceptanceTest::ids).toList()));
    }

    /** M-08b: 2~3명 확정 자격이 없으면(10분 전, 마감 전) 4명 ABCD만. */
    @Test
    void m08bOnlyFullGroupWithoutEarlyConfirm() {
        Instant asked = SLOT.minusSeconds(300);
        List<MatchRequest> requests = List.of(
                reserved(1, HUB, 3, asked), reserved(2, HUB, 3.5, asked), reserved(3, HUB, 4, asked),
                reserved(4, HUB, 4.5, asked), reserved(5, HUB, 5, asked), reserved(6, HUB, 5.5, asked));

        RoundResult result = round.run(SLOT, requests, ONLY_ALLOWED, ONLY_THREE);

        assertEquals(List.of(Set.of(1L, 2L, 3L, 4L)), result.confirmed().stream().map(SpecAcceptanceTest::ids).toList());
    }

    /** M-09: 가장 먼저 요청 8분 전 → 이번 회차 대기, 다음 예약 회차(10분 지남) 확정. */
    @Test
    void m09TenMinuteRule() {
        Instant first = SLOT.minusSeconds(480);
        List<MatchRequest> requests = List.of(
                reserved(1, HUB, 3, first), reserved(2, HUB, 4, SLOT.minusSeconds(60)), reserved(3, HUB, 5, SLOT));

        assertTrue(round.run(SLOT, requests, LEGS, PASS).confirmed().isEmpty());
        RoundResult later = round.run(SLOT.plusSeconds(300), requests, LEGS, PASS);
        assertEquals(1, later.confirmed().size());
        assertEquals(3, later.confirmed().get(0).order().size());
    }

    /** M-10: 즉시 요청 2명은 1분 회차에서 찾은 즉시 확정. 예약만인 일행은 1분 회차에서 확정하지 않는다. */
    @Test
    void m10InstantConfirmsWhenFound() {
        MatchingRound everyMinute = new MatchingRound(MatchingSettings.everyMinute());
        Instant slot = SLOT.plusSeconds(60);
        var a = Fixtures.of(1, 101, HUB, Gender.FEMALE, slot.plusSeconds(1500), slot.plusSeconds(3300), at(3, 0))
                .requested(slot, true);
        var b = Fixtures.of(2, 102, HUB, Gender.FEMALE, slot.plusSeconds(1500), slot.plusSeconds(3300), at(4, 0))
                .requested(slot, true);
        var c = reserved(3, HUB, 3, slot.minusSeconds(1200)).requested(slot.minusSeconds(1200), false);
        var d = reserved(4, HUB, 4, slot.minusSeconds(1200));

        RoundResult result = everyMinute.run(slot, List.of(a, b), LEGS, PASS);
        assertEquals(1, result.confirmed().size());
        // S37·S41: 확정 8:31 + max(결제 5분, 걷기 0분) + 배차 여유 5분 = 8:41
        assertEquals(slot.plusSeconds(600), result.confirmed().get(0).departure());
        assertTrue(everyMinute.run(slot, List.of(c, d), LEGS, PASS).confirmed().isEmpty());
    }

    private static final Instant DAY = Instant.parse("2026-11-20T00:00:00Z");

    private static MatchRequest arriving(long id, double km, String earliest, String latest, int ride) {
        return MatchRequest.arriving(id, id + 100, HUB, Gender.FEMALE, DAY.plusSeconds(hm(earliest)),
                DAY.plusSeconds(hm(latest)), DAY.plusSeconds(hm("08:00")), at(km, 0), km, ride);
    }

    private static long hm(String text) {
        String[] p = text.split(":");
        return Long.parseLong(p[0]) * 3600 + Long.parseLong(p[1]) * 60;
    }

    /** M-11: A 8:50~9:00, B 8:55~9:05, A 하차 8:52, B 9:00 → 통과. M-11b: A 하차 8:45 → 탈락. */
    @Test
    void m11ArrivalWindowsForEachRider() {
        var a = arriving(1, 5, "08:50", "09:00", 1200);
        var b = arriving(2, 7, "08:55", "09:05", 1680);
        EstimateCalculator calc = new EstimateCalculator(settings);
        Instant slot = DAY.plusSeconds(hm("08:00"));

        Candidate ok = calc.evaluate(List.of(a, b), (x, y) -> Optional.of(new RoadLeg(2, 480)), slot).orElseThrow();
        assertEquals(DAY.plusSeconds(hm("08:32")), ok.departure());

        var bLong = arriving(2, 7, "08:55", "09:05", 2100);
        assertTrue(calc.evaluate(List.of(a, bLong), (x, y) -> Optional.of(new RoadLeg(2, 900)), slot).isEmpty());

        RoundResult result = round.run(slot, List.of(a, b), (x, y) -> Optional.of(new RoadLeg(2, 480)), PASS);
        assertEquals(1, result.confirmed().size());
        assertEquals(DAY.plusSeconds(hm("08:32")), result.confirmed().get(0).departure());
    }

    /** 출발 기준과 도착 기준은 섞지 않는다. */
    @Test
    void departureAndArrivalRequestsDoNotMix() {
        var a = arriving(1, 5, "08:50", "09:00", 1200);
        var b = Fixtures.of(2, 102, HUB, Gender.FEMALE, DAY.plusSeconds(hm("08:30")), DAY.plusSeconds(hm("09:00")),
                at(6, 0));
        assertTrue(round.run(DAY.plusSeconds(hm("08:00")), List.of(a, b), LEGS, PASS).confirmed().isEmpty());
    }

    /** T-01: 범위 18:00~18:40, 마감 회차 18:15 확정 → T 18:40. */
    @Test
    void t01DepartureAtDeadlineRound() {
        FromHubPlan plan = new FromHubPlan(settings);
        var a = Fixtures.of(1, 101, HUB, Gender.FEMALE, DAY.plusSeconds(hm("18:00")), DAY.plusSeconds(hm("18:40")),
                at(3, 0));
        var b = Fixtures.of(2, 102, HUB, Gender.FEMALE, DAY.plusSeconds(hm("18:00")), DAY.plusSeconds(hm("18:40")),
                at(4, 0));
        Instant slot = DAY.plusSeconds(hm("18:15"));
        assertTrue(plan.isDeadline(List.of(a, b), slot));
        assertFalse(plan.isDeadline(List.of(a, b), slot.minusSeconds(300)));
        assertEquals(DAY.plusSeconds(hm("18:40")), plan.schedule(List.of(a, b), List.of(600, 900), slot).departure());
    }

    /** T-02: A 끝 18:20, B 끝 18:40 → 일행 마감 17:55. */
    @Test
    void t02GroupDeadlineIsEarliestMember() {
        FromHubPlan plan = new FromHubPlan(settings);
        var a = Fixtures.of(1, 101, HUB, Gender.FEMALE, DAY.plusSeconds(hm("17:30")), DAY.plusSeconds(hm("18:20")),
                at(3, 0));
        var b = Fixtures.of(2, 102, HUB, Gender.FEMALE, DAY.plusSeconds(hm("17:30")), DAY.plusSeconds(hm("18:40")),
                at(4, 0));
        assertEquals(DAY.plusSeconds(hm("17:55")), plan.deadline(a));
        assertFalse(plan.isDeadline(List.of(a, b), DAY.plusSeconds(hm("17:50"))));
        assertTrue(plan.isDeadline(List.of(a, b), DAY.plusSeconds(hm("17:55"))));
    }

    /** I-02 (S37): 즉시 실패 시각 = 요청 + 5분. 18:00 요청, 같이 탈 사람 없음 → 18:05 회차에 실패. */
    @Test
    void i02InstantFailsFiveMinutesAfterRequest() {
        FromHubPlan plan = new FromHubPlan(settings);
        var a = Fixtures.of(1, 101, HUB, Gender.FEMALE, DAY.plusSeconds(hm("18:00")), DAY.plusSeconds(hm("18:30")),
                at(3, 0)).requested(DAY.plusSeconds(hm("18:00")), true);
        assertEquals(DAY.plusSeconds(hm("18:05")), plan.deadline(a));
        MatchingRound everyMinute = new MatchingRound(MatchingSettings.everyMinute());
        assertTrue(everyMinute.run(DAY.plusSeconds(hm("18:04")), List.of(a), LEGS, PASS).expired().isEmpty());
        assertEquals(List.of(a), everyMinute.run(DAY.plusSeconds(hm("18:05")), List.of(a), LEGS, PASS).expired());
    }

    /** I-03 (S37, S41): 18:02 확정 → 예상 T = 18:02 + max(5, 가장 긴 걷기) + 5 = 18:12. 걷기 상한이 5분이라 늘 확정 10분 뒤다. */
    @Test
    void i03InstantDepartureFollowsLongestWalk() {
        FromHubPlan plan = new FromHubPlan(settings);
        Instant asked = DAY.plusSeconds(hm("18:00"));
        var a = Fixtures.of(1, 101, HUB, Gender.FEMALE, asked, asked.plusSeconds(1800), at(3, 0))
                .requested(asked, true);
        var b = Fixtures.of(2, 102, HUB, Gender.FEMALE, asked, asked.plusSeconds(1800), at(4, 0))
                .requested(asked, true);
        b = b.atPoint(240, b.anchorKm(), b.anchorSeconds());
        Instant slot = DAY.plusSeconds(hm("18:02"));
        assertEquals(DAY.plusSeconds(hm("18:12")), plan.schedule(List.of(a, b), List.of(600, 900), slot).departure());
        // 걷기가 짧아도 결제 준비 5분이 기준: 18:02 + 5 + 5 = 18:12
        var c = a.atPoint(60, a.anchorKm(), a.anchorSeconds());
        assertEquals(DAY.plusSeconds(hm("18:12")), plan.schedule(List.of(c), List.of(600), slot).departure());
    }

    /**
     * S37: 즉시 일행은 늘 즉시 출발한다. 예약 요청자는 즉시 예상 T가 자기 출발 가능 범위 안일 때만 섞인다.
     * 범위가 더 늦게 시작하면 T를 미루지 않고 일행이 안 된다. 즉시는 도착 기준 불가.
     */
    @Test
    void instantGroupRespectsReservationWindow() {
        FromHubPlan plan = new FromHubPlan(settings);
        Instant slot = DAY.plusSeconds(hm("18:02"));
        var now = Fixtures.of(1, 101, HUB, Gender.FEMALE, slot, slot.plusSeconds(1800), at(3, 0)).requested(slot, true);
        Instant instantT = plan.schedule(List.of(now), List.of(600), slot).departure();
        var inside = Fixtures.of(4, 104, HUB, Gender.FEMALE, DAY.plusSeconds(hm("18:05")), DAY.plusSeconds(hm("18:50")),
                at(4, 0));
        assertEquals(instantT, plan.schedule(List.of(now, inside), List.of(600, 900), slot).departure());
        var later = Fixtures.of(2, 102, HUB, Gender.FEMALE, DAY.plusSeconds(hm("18:20")), DAY.plusSeconds(hm("18:50")),
                at(4, 0));
        assertFalse(plan.schedule(List.of(now, later), List.of(600, 900), slot).ok());
        var early = Fixtures.of(3, 103, HUB, Gender.FEMALE, DAY.plusSeconds(hm("17:50")), DAY.plusSeconds(hm("18:05")),
                at(4, 0));
        assertFalse(plan.schedule(List.of(now, early), List.of(600, 900), slot).ok());
    }

    /** 늦게 시작하는 출발 범위: T는 교집합 시작(5분 단위) 이후. */
    @Test
    void departureWaitsForLatestWindowStart() {
        FromHubPlan plan = new FromHubPlan(settings);
        var a = Fixtures.of(1, 101, HUB, Gender.FEMALE, SLOT.plusSeconds(1200), SLOT.plusSeconds(3600), at(3, 0));
        var b = Fixtures.of(2, 102, HUB, Gender.FEMALE, SLOT.plusSeconds(2400), SLOT.plusSeconds(4800), at(4, 0));
        var late = Fixtures.of(3, 103, HUB, Gender.FEMALE, SLOT.plusSeconds(4200), SLOT.plusSeconds(4800), at(4, 0));
        assertEquals(SLOT.plusSeconds(2400), plan.schedule(List.of(a, b), List.of(600, 900), SLOT).departure());
        assertFalse(plan.schedule(List.of(a, late), List.of(600, 900), SLOT).ok());
    }

    /** 명세 2장: 같이 내리는(같은 거리) 사람이 여럿이면 요청 번호가 빠른 사람이 결제자. */
    @Test
    void payerTieGoesToEarlierRequest() {
        var first = Fixtures.of(1, 101, HUB, Gender.FEMALE, SLOT.plusSeconds(1200), SLOT.plusSeconds(3600), at(5, 0));
        var second = Fixtures.of(2, 102, HUB, Gender.FEMALE, SLOT.plusSeconds(1200), SLOT.plusSeconds(3600), at(5, 0));
        var near = Fixtures.of(3, 103, HUB, Gender.FEMALE, SLOT.plusSeconds(1200), SLOT.plusSeconds(3600), at(3, 0));
        TripPlan plan = TripPlan.of(Direction.FROM_HUB, settings);

        List<MatchRequest> order = plan.order(List.of(first, second, near));
        assertEquals(List.of(near, second, first), order);
        assertEquals(first, plan.payer(order));
    }

    /** 같은 회원의 요청이 둘로 들어와도(서버 규칙 위반) 한 일행에 같이 넣지 않는다. */
    @Test
    void sameMemberIsNeverGroupedWithItself() {
        Instant asked = SLOT.minusSeconds(900);
        var first = reserved(1, HUB, 3, asked);
        var duplicate = Fixtures.of(2, first.memberId(), HUB, Gender.FEMALE, SLOT.plusSeconds(1200),
                SLOT.plusSeconds(3600), at(4, 0)).requested(asked, false);

        RoundResult result = round.run(SLOT, List.of(first, duplicate), LEGS, PASS);

        assertTrue(result.confirmed().isEmpty());
    }

    /** 구간 조회가 느려도(한 번에 220ms) 회차 계산은 2초 안에 끝난다. 시간 한도를 넘으면 그때까지의 조합으로 고른다. */
    @Test
    void slowLegLookupsStayWithinTwoSeconds() {
        Instant asked = SLOT.minusSeconds(900);
        List<MatchRequest> requests = List.of(reserved(1, HUB, 3, asked), reserved(2, HUB, 3.5, asked),
                reserved(3, HUB, 4, asked), reserved(4, HUB, 4.5, asked));
        LegProvider slow = (x, y) -> {
            try {
                Thread.sleep(220);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return LEGS.leg(x, y);
        };

        long start = System.nanoTime();
        round.run(SLOT, requests, slow, PASS);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(millis < 2000, "took " + millis + "ms");
    }

    /** 명세 5.2: 도착 기준은 2차 실제 경로로 T를 다시 정한다. 검사가 돌려준 T로 확정한다. */
    @Test
    void verifiedDepartureFromSecondCheckIsUsed() {
        var a = arriving(1, 5, "08:50", "09:00", 1200);
        var b = arriving(2, 7, "08:55", "09:05", 1680);
        Instant slot = DAY.plusSeconds(hm("08:00"));
        Instant later = DAY.plusSeconds(hm("08:35"));   // 실제 경로가 5분 더 걸려 T를 당김
        GroupVerifier slower = new GroupVerifier() {
            @Override
            public Verdict verify(Candidate c) {
                return Verdict.PASS;
            }

            @Override
            public Verification check(Candidate c) {
                assertEquals(DAY.plusSeconds(hm("08:32")), c.departure());   // 1차 추정
                return new Verification(Verdict.PASS, later);
            }
        };

        RoundResult result = round.run(slot, List.of(a, b), (x, y) -> Optional.of(new RoadLeg(2, 480)), slower);

        assertEquals(later, result.confirmed().get(0).departure());
    }
}
