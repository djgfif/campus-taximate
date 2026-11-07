package com.campustaximate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.campustaximate.settlement.SettlementCalculator;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToSchoolPlanTest {

    private static final Instant ARRIVE = Instant.parse("2026-11-20T09:00:00Z");
    private static final Instant SLOT = Fixtures.SLOT;
    private static final TripPlan PLAN = TripPlan.of(Direction.TO_SCHOOL);

    private static MatchRequest request(long id, double km, Instant arriveBy, Instant deadline) {
        GeoPoint own = Fixtures.bearing(90, km);
        int seconds = (int) Math.round(km * 1.3 / 24 * 3600);
        return MatchRequest.toSchool(id, id + 100, 1, Fixtures.ORIGIN, Gender.FEMALE, arriveBy, deadline, own,
                km * 1.3, seconds);
    }

    private static MatchRequest request(long id, double km) {
        return request(id, km, ARRIVE, SLOT.plusSeconds(3600));
    }

    @Test
    void farthestBoardsFirstAndPays() {
        MatchRequest near = request(1, 4);
        MatchRequest middle = request(2, 7);
        MatchRequest far = request(3, 10);
        List<MatchRequest> order = PLAN.order(List.of(near, far, middle));
        assertEquals(List.of(far, middle, near), order);
        assertSame(far, PLAN.payer(order));
    }

    @Test
    void rideTimeAddsTwoMinutesForEachStopLeft() {
        MatchRequest far = request(1, 10);
        MatchRequest near = request(2, 4);
        List<Integer> rides = PLAN.rideSeconds(List.of(far, near), List.of(new RoadLeg(6, 600)));
        assertEquals(near.soloSeconds(), rides.get(1));
        assertEquals(near.soloSeconds() + 600 + ToSchoolPlan.STOP_SECONDS, rides.get(0));
    }

    @Test
    void poolStaysOpenUntilDeadlineSlotAndExpiresThere() {
        MatchRequest request = request(1, 5);
        Instant deadline = request.deadlineSlot();
        assertTrue(PLAN.eligible(request, deadline));
        assertFalse(PLAN.eligible(request, deadline.plusSeconds(300)));
        assertFalse(PLAN.expired(request, deadline.minusSeconds(300)));
        assertTrue(PLAN.expired(request, deadline));
    }

    @Test
    void twoRidersConfirmAtEarliestDeadlineOfTheGroup() {
        MatchRequest early = request(1, 10, ARRIVE, SLOT);
        MatchRequest late = request(2, 5, ARRIVE, SLOT.plusSeconds(900));
        assertFalse(PLAN.isDeadline(List.of(early, late), SLOT.minusSeconds(300)));
        assertTrue(PLAN.isDeadline(List.of(early, late), SLOT));
    }

    @Test
    void differentArrivalTimesNeverShareAPool() {
        MatchingRound round = new MatchingRound(MatchingSettings.defaults());
        MatchRequest a = request(1, 10, ARRIVE, SLOT);
        MatchRequest b = request(2, 5, ARRIVE.plusSeconds(300), SLOT);
        RoundResult result = round.run(SLOT, List.of(a, b), Fixtures.LEGS, c -> Verdict.PASS);
        assertTrue(result.confirmed().isEmpty());
        assertEquals(2, result.expired().size());
    }

    @Test
    void roundConfirmsFarthestFirstWithNoFixedDeparture() {
        MatchingRound round = new MatchingRound(MatchingSettings.defaults());
        MatchRequest near = request(1, 4);
        MatchRequest middle = request(2, 7);
        MatchRequest far = request(3, 10);
        RoundResult result = round.run(SLOT, List.of(near, middle, far), Fixtures.LEGS, c -> Verdict.PASS);
        ConfirmedGroup group = result.confirmed().get(0);
        assertEquals(List.of(far, middle, near), group.order());
        assertSame(far, group.payer());
        assertEquals(null, group.departure());
    }

    @Test
    void firstPositionCanBeThePayerInSettlement() {
        List<Long> shares = SettlementCalculator.shares(15000, List.of(13200L, 9800L, 7000L), 0);
        assertEquals(List.of(6600L, 4900L, 3500L), shares);
        List<Long> rounded = SettlementCalculator.shares(15400, List.of(13200L, 9800L, 7000L), 0);
        assertEquals(List.of(6700L, 5100L, 3600L), rounded);
    }
}
