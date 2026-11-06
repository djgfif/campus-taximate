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

        assertEquals(List.of(near, middle, far), candidate.dropoffOrder());
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

    private static MatchingSettings withMaxDelay(int minutes) {
        MatchingSettings d = MatchingSettings.defaults();
        return new MatchingSettings(minutes, d.minSavingRate(), d.maxCandidates(), d.maxGroupSize(),
                d.maxVerifications(), d.leadMinutes(), d.slotMinutes());
    }

    @Test
    void rejectsWhenSomeoneSavesLessThanTenPercent() {
        var east = request(1, at(1, 0));
        var west = request(2, at(-2, 0));

        assertTrue(calculator.evaluate(List.of(east, west), LEGS).isEmpty());
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
