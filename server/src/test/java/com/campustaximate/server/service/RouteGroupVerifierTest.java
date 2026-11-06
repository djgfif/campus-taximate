package com.campustaximate.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import com.campustaximate.matching.*;
import com.campustaximate.server.route.RouteClient;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteGroupVerifierTest {
    private final RouteClient routes = mock(RouteClient.class);
    private final Instant now = Instant.parse("2026-11-06T00:00:00Z");
    private final Hub hub = new Hub(1, new GeoPoint(37, 127));

    private Candidate candidate(int size) {
        return new Candidate(java.util.stream.IntStream.rangeClosed(1, size)
                .mapToObj(i -> new MatchRequest(i, i, hub, Gender.FEMALE, now, now.plusSeconds(3600),
                        new GeoPoint(37 + i * 0.01, 127), i, 100)).toList(), 10);
    }

    private Verdict check(long total, int middleSeconds) {
        Candidate c = candidate(3);
        when(routes.route(any(), any())).thenAnswer(call -> {
            List<GeoPoint> stops = call.getArgument(1);
            return new RouteClient.Route(5, stops.size() == 2 ? middleSeconds : 1200,
                    stops.size() == 3 ? total : 10000);
        });
        return new RouteGroupVerifier(routes, new HashMap<>(), now).verify(c);
    }

    @Test
    void totalFareAtNinetyPercentPassesEvenWithoutIndividualSaving() {
        assertThat(check(27000, 1200)).isEqualTo(Verdict.PASS);
    }

    @Test
    void totalFareAboveNinetyPercentFails() {
        assertThat(check(27001, 1200)).isEqualTo(Verdict.FAIL);
    }

    @Test
    void intermediateRiderUsesOwnCumulativeSummaryTime() {
        // 마지막 시간보다 작은 중간 누적 시간도 그 사람의 혼자 시간과 비교한다.
        when(routes.route(any(), any())).thenAnswer(call -> {
            List<GeoPoint> stops = call.getArgument(1);
            int seconds = stops.size() == 1 ? (stops.getFirst().lat() > 37.025 ? 200 : 100)
                    : stops.size() == 2 ? 1001 : 1100;
            return new RouteClient.Route(5, seconds, 10000);
        });
        assertThat(new RouteGroupVerifier(routes, new HashMap<>(), now).verify(candidate(3)))
                .isEqualTo(Verdict.FAIL);
    }

    @Test
    void exactlyFifteenMinutesPassesAndDecreasingFareIsAllowed() {
        when(routes.route(any(), any())).thenAnswer(call -> {
            List<GeoPoint> stops = call.getArgument(1);
            return new RouteClient.Route(5, stops.size() == 1 ? 100 : 1000,
                    stops.size() == 1 ? 10000 : 9000);
        });
        assertThat(new RouteGroupVerifier(routes, new HashMap<>(), now).verify(candidate(3)))
                .isEqualTo(Verdict.PASS);
    }

    private Candidate sameDropoff(int size) {
        return new Candidate(java.util.stream.IntStream.rangeClosed(1, size)
                .mapToObj(i -> new MatchRequest(i, i, hub, Gender.FEMALE, now, now.plusSeconds(3600),
                        new GeoPoint(37.01, 127), 5, 100)).toList(), 5);
    }

    @Test
    void sameDropoffSharesOneRouteAndPayerFareStaysSolo() {
        when(routes.route(any(), any())).thenReturn(new RouteClient.Route(5, 100, 10000));
        var groupVerifier = new RouteGroupVerifier(routes, new HashMap<>(), now);
        Candidate c = sameDropoff(3);
        assertThat(groupVerifier.verify(c)).isEqualTo(Verdict.PASS);
        verify(routes, times(3)).route(any(), any());
        assertThat(groupVerifier.routesOf(c.dropoffOrder()).cumulative().getLast().fare()).isEqualTo(10000);
    }

    @Test
    void sameDropoffLegNeedsNoCall() {
        var requests = sameDropoff(2).dropoffOrder();
        var store = new RoadLegStore(routes);
        store.prepare(requests, now);
        assertThat(store.provider().leg(requests.get(0), requests.get(1))).contains(new RoadLeg(0, 0));
        org.mockito.Mockito.verifyNoInteractions(routes);
    }

    @Test
    void fourRidersRequireOnlySevenCalls() {
        when(routes.route(any(), any())).thenReturn(new RouteClient.Route(5, 100, 10000));
        assertThat(new RouteGroupVerifier(routes, new HashMap<>(), now).verify(candidate(4)))
                .isEqualTo(Verdict.PASS);
        verify(routes, times(7)).route(any(), any());
    }
}
