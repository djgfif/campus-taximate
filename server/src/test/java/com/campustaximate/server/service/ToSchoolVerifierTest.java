package com.campustaximate.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.campustaximate.matching.Candidate;
import com.campustaximate.matching.Gender;
import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.MatchRequest;
import com.campustaximate.matching.RoadLeg;
import com.campustaximate.matching.ToSchoolPlan;
import com.campustaximate.matching.Verdict;
import com.campustaximate.server.route.RouteClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ToSchoolVerifierTest {

    private static final GeoPoint SCHOOL = new GeoPoint(37.9, 127.0);
    private final Instant now = Instant.parse("2026-11-20T00:00:00Z");
    private final Instant arriveBy = Instant.parse("2026-11-20T09:00:00Z");

    /** 구간마다 legSeconds초가 걸리고, 출발 시각이 fasterAfter 이전이면 그 값대로 간다. */
    private static final class FakeRoutes implements RouteClient {
        final List<Instant> sharedDepartures = new ArrayList<>();
        int calls;
        int legSeconds = 600;
        int soloSeconds = 1500;
        long sharedFare = 15000;
        long soloFare = 10000;
        int extraEachCall;
        int[] lookups;
        boolean noSections;

        @Override
        public Route route(GeoPoint origin, List<GeoPoint> stops) {
            throw new AssertionError("등교는 미래 조회를 쓴다");
        }

        @Override
        public Route routeAt(GeoPoint origin, List<GeoPoint> stops, Instant departure) {
            calls++;
            if (stops.size() == 1) {
                return new Route(5, soloSeconds, soloFare, List.of(), List.of(soloSeconds));
            }
            sharedDepartures.add(departure);
            int leg = lookups != null ? lookups[sharedDepartures.size() - 1]
                    : legSeconds + extraEachCall * sharedDepartures.size();
            List<Integer> legs = Collections.nCopies(stops.size(), leg);
            return new Route(9, leg * stops.size(), sharedFare, List.of(), noSections ? List.of() : legs);
        }

        @Override
        public Map<String, RoadLeg> legs(GeoPoint origin, Map<String, GeoPoint> destinations) {
            return Map.of();
        }

        @Override
        public boolean simulated() {
            return true;
        }
    }

    private Candidate candidate(int size) {
        List<MatchRequest> order = IntStream.rangeClosed(1, size)
                .mapToObj(i -> MatchRequest.toSchool(i, i, 1, SCHOOL, Gender.FEMALE, arriveBy,
                        arriveBy.minus(Duration.ofHours(2)), new GeoPoint(37.0 + i * 0.1, 127.0), 10.0 - i, 1500))
                .toList();
        return new Candidate(order, 10);
    }

    private RouteGroupVerifier verifier(FakeRoutes routes) {
        return new RouteGroupVerifier(routes, new HashMap<>(), now);
    }

    @Test
    void backtracksDepartureAndSpreadsBoardingTimesWithStops() {
        FakeRoutes routes = new FakeRoutes();
        Candidate candidate = candidate(3);
        RouteGroupVerifier verifier = verifier(routes);

        assertThat(verifier.verify(candidate)).isEqualTo(Verdict.PASS);

        var result = verifier.routesOf(candidate.order());
        int stop = ToSchoolPlan.STOP_SECONDS;
        assertThat(result.boardAt().get(1)).isEqualTo(result.departure().plusSeconds(600 + stop));
        assertThat(result.boardAt().get(2)).isEqualTo(result.departure().plusSeconds(1200 + 2 * stop));
        Instant arrival = result.departure().plusSeconds(1800 + 2L * stop);
        assertThat(arrival).isBeforeOrEqualTo(arriveBy);
        assertThat(result.rideSeconds().get(0)).isEqualTo(1800 + 2 * stop);
        assertThat(result.rideSeconds().get(1)).isEqualTo(1200 + stop);
        assertThat(result.rideSeconds().get(2)).isEqualTo(1500);
    }

    @Test
    void fourRidersUseAtMostNineCalls() {
        FakeRoutes routes = new FakeRoutes();
        routes.lookups = new int[] {500, 560, 500};
        routes.soloSeconds = 2000;
        routes.sharedFare = 30000;

        assertThat(verifier(routes).verify(candidate(4))).isEqualTo(Verdict.PASS);

        assertThat(routes.sharedDepartures).hasSize(3);
        assertThat(routes.calls).isLessThanOrEqualTo(9);
    }

    @Test
    void failsWhenThreeLookupsCannotReachTheArrivalTime() {
        FakeRoutes routes = new FakeRoutes();
        routes.extraEachCall = 600;

        assertThat(verifier(routes).verify(candidate(3))).isEqualTo(Verdict.FAIL);

        assertThat(routes.sharedDepartures).hasSize(3);
    }

    @Test
    void delayOverFifteenMinutesFails() {
        FakeRoutes routes = new FakeRoutes();
        routes.legSeconds = 1000;
        routes.soloSeconds = 1000;

        assertThat(verifier(routes).verify(candidate(3))).isEqualTo(Verdict.FAIL);
    }

    @Test
    void lessThanTenPercentSavingFails() {
        FakeRoutes routes = new FakeRoutes();
        routes.sharedFare = 27100;

        assertThat(verifier(routes).verify(candidate(3))).isEqualTo(Verdict.FAIL);
    }

    @Test
    void missingSectionTimesAreTreatedAsUnavailable() {
        FakeRoutes routes = new FakeRoutes();
        routes.noSections = true;

        assertThat(verifier(routes).verify(candidate(3))).isEqualTo(Verdict.UNAVAILABLE);
    }

    @Test
    void departureInThePastFails() {
        FakeRoutes routes = new FakeRoutes();

        assertThat(new RouteGroupVerifier(routes, new HashMap<>(), arriveBy).verify(candidate(3)))
                .isEqualTo(Verdict.FAIL);
    }
}
