package com.campustaximate.matching;

import static com.campustaximate.matching.Fixtures.LEGS;
import static com.campustaximate.matching.Fixtures.at;
import static com.campustaximate.matching.Fixtures.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EstimateCalculatorTest {

    private final EstimateCalculator calculator = new EstimateCalculator(MatchingSettings.defaults());

    @Test
    void passesForSameDirectionAndOrdersNearestFirst() {
        var far = request(1, at(7, 0));
        var near = request(2, at(3, 0));
        var middle = request(3, at(5, 0));

        Candidate candidate = calculator.evaluate(List.of(far, near, middle), LEGS).orElseThrow();

        assertEquals(List.of(near, middle, far), candidate.order());
        assertEquals(7 * 1.3, candidate.totalKm(), 0.05);
    }

    @Test
    void rejectsWhenDelayIsOverFifteenMinutes() {
        // 네 명이 나눠 절약 조건은 통과하고, 두 번째 지점의 지연만 약 23분이다.
        var firstA = request(1, at(6, 6));
        var firstB = request(2, at(6, 6));
        var secondA = request(3, at(9, -1));
        var secondB = request(4, at(9, -1));

        assertTrue(calculator.evaluate(List.of(firstA, firstB, secondA, secondB), LEGS).isEmpty());
        assertFalse(new EstimateCalculator(withMaxDelay(30))
                .evaluate(List.of(firstA, firstB, secondA, secondB), LEGS).isEmpty());
    }

    /** 명세 4.3: 거리 절약은 1차에서 거르지 않고 순위에만 쓴다. 요금 10%는 2차가 본다. */
    @Test
    void distanceSavingOnlyRanks() {
        var a = new MatchRequest(1, 101, Fixtures.HUB, Gender.FEMALE,
                Fixtures.SLOT, Fixtures.SLOT.plusSeconds(3600), at(1, 0), 10, 100);
        var b = new MatchRequest(2, 102, Fixtures.HUB, Gender.FEMALE,
                Fixtures.SLOT, Fixtures.SLOT.plusSeconds(3600), at(2, 0), 10, 100);
        Candidate shorter = calculator.evaluate(List.of(a, b), (x, y) ->
                java.util.Optional.of(new RoadLeg(8, 100))).orElseThrow();
        Candidate longer = calculator.evaluate(List.of(a, b), (x, y) ->
                java.util.Optional.of(new RoadLeg(12, 100))).orElseThrow();
        assertEquals(2.0, shorter.savedKm(), 1e-9);
        assertEquals(-2.0, longer.savedKm(), 1e-9);
    }

    private static MatchRequest timed(long id, double anchorKm, int solo, int walk, int anchorSeconds) {
        return new MatchRequest(id, id + 100, Fixtures.HUB, Gender.FEMALE, Fixtures.SLOT,
                Fixtures.SLOT.plusSeconds(3600), at(anchorKm, 0), anchorKm, solo)
                .atPoint(walk, anchorKm, anchorSeconds);
    }

    /** M-05: 걷기 8분 + 동승 25분, 단독 20분 → 지연 13분, 통과. */
    @Test
    void walkingCountsTowardDelay() {
        var a = timed(1, 5, 1200, 480, 1500);
        var b = timed(2, 6, 1600, 0, 1600);
        Candidate c = calculator.evaluate(List.of(a, b), (x, y) ->
                java.util.Optional.of(new RoadLeg(1, 100))).orElseThrow();
        assertEquals(780, c.maxDelay());
    }

    /** M-03, M-04: 지연 900초 통과, 901초 탈락. */
    @Test
    void delayBoundaryIsNineHundredSeconds() {
        var a = timed(1, 5, 1500, 0, 1500);
        var b = timed(2, 8, 1000, 0, 1000);
        assertTrue(calculator.evaluate(List.of(a, b), (x, y) ->
                java.util.Optional.of(new RoadLeg(3, 400))).isPresent());
        assertTrue(calculator.evaluate(List.of(a, b), (x, y) ->
                java.util.Optional.of(new RoadLeg(3, 401))).isEmpty());
    }

    private static MatchingSettings withMaxDelay(int minutes) {
        MatchingSettings d = MatchingSettings.defaults();
        return new MatchingSettings(minutes, d.minSavingRate(), d.maxCandidates(), d.maxGroupSize(),
                d.maxVerifications(), d.leadMinutes(), d.slotMinutes());
    }

    /** 반대 방향은 1차를 지나도 절약이 음수라 순위가 맨 뒤다. 실제 탈락은 2차 요금 검사. */
    @Test
    void oppositeDirectionsSaveNothing() {
        var east = request(1, at(1, 0));
        var west = request(2, at(-2, 0));

        assertTrue(calculator.evaluate(List.of(east, west), LEGS).orElseThrow().savedKm() < 0);
    }

    @Test
    void rejectsGroupWhenRoadLegIsUnknown() {
        var near = request(1, at(3, 0));
        var far = request(2, at(5, 0));

        assertTrue(calculator.evaluate(List.of(near, far), (a, b) -> java.util.Optional.empty()).isEmpty());
    }

    @Test
    void realRoadDetourRejectsGroupThatLooksFineInStraightLine() {
        var near = request(1, at(3, 0));
        var far = request(2, at(5, 0));

        assertTrue(calculator.evaluate(List.of(near, far), (a, b) ->
                java.util.Optional.of(new RoadLeg(3, 30 * 60))).isEmpty());
    }

    @Test
    void acceptsSmallDetour() {
        var near = request(1, at(10, 0));
        var offRoute = request(2, at(10, 2));

        assertFalse(calculator.evaluate(List.of(near, offRoute), LEGS).isEmpty());
    }
}
