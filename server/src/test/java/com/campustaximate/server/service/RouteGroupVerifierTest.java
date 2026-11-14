package com.campustaximate.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.campustaximate.matching.*;
import com.campustaximate.server.route.RouteClient;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RouteGroupVerifierTest {
    private final RouteClient routes = mock(RouteClient.class);
    private final Instant now = Instant.parse("2026-11-06T00:00:00Z");
    private final Hub hub = new Hub(1, new GeoPoint(37, 127));

    private Candidate candidate(int size) {
        return new Candidate(java.util.stream.IntStream.rangeClosed(1, size)
                .mapToObj(i -> new MatchRequest(i, i, hub, Gender.FEMALE, now, now.plusSeconds(3600),
                        new GeoPoint(37 + i * 0.01, 127), i, 100)).toList(), 10, now.plusSeconds(1500), List.of());
    }

    private void quote(long total, int middleSeconds, int soloSeconds) {
        when(routes.routeAt(any(), anyList(), any())).thenAnswer(call -> {
            List<GeoPoint> stops = call.getArgument(1);
            if (stops.size() == 1) return new RouteClient.Route(5, soloSeconds, 10000, List.of(), List.of(soloSeconds));
            return new RouteClient.Route(5, 1200, total, List.of(), List.of(100, middleSeconds - 100, 1200 - middleSeconds));
        });
    }

    private Verdict check(long total, int middleSeconds) {
        quote(total, middleSeconds, 1200);
        return new RouteGroupVerifier(routes, new HashMap<>(), now).verify(candidate(3));
    }

    @Test
    void soloFareStaysAsShownAtRequestEvenIfRouteQuoteChanges() {
        quote(20000, 600, 1200);
        Candidate c = candidate(3);
        Map<Long, Long> fares = new HashMap<>();
        c.order().forEach(r -> fares.put(r.id(), 12000L));
        var verifier = new RouteGroupVerifier(routes, new HashMap<>(), now, null, fares);
        assertThat(verifier.verify(c)).isEqualTo(Verdict.PASS);
        assertThat(verifier.routesOf(c.order()).solo()).allMatch(r -> r.fare() == 12000);
    }

    @Test
    void totalFareAtNinetyPercentPassesEvenWithoutIndividualSaving() {
        assertThat(check(27000, 600)).isEqualTo(Verdict.PASS);
    }

    @Test
    void totalFareAboveNinetyPercentFails() {
        assertThat(check(27001, 600)).isEqualTo(Verdict.FAIL);
    }

    @Test
    void intermediateRiderUsesOwnCumulativeSummaryTime() {
        // 전체 경로의 중간 하차 누적 1001초, 그 사람의 단독 100초이면 지연 901초다.
        quote(10000, 1001, 1200);
        GeoPoint middle = candidate(3).order().get(1).own();
        when(routes.routeAt(eq(hub.location()), eq(List.of(middle)), any()))
                .thenReturn(new RouteClient.Route(5, 100, 10000));
        assertThat(new RouteGroupVerifier(routes, new HashMap<>(), now).verify(candidate(3))).isEqualTo(Verdict.FAIL);
    }

    @Test
    void exactlyFifteenMinutesPassesAndDecreasingFareIsAllowed() {
        quote(9000, 1000, 1200);
        GeoPoint middle = candidate(3).order().get(1).own();
        when(routes.routeAt(eq(hub.location()), eq(List.of(middle)), any()))
                .thenReturn(new RouteClient.Route(5, 100, 10000));
        assertThat(new RouteGroupVerifier(routes, new HashMap<>(), now).verify(candidate(3))).isEqualTo(Verdict.PASS);
    }

    private Candidate sameDropoff(int size) {
        return new Candidate(java.util.stream.IntStream.rangeClosed(1, size)
                .mapToObj(i -> new MatchRequest(i, i, hub, Gender.FEMALE, now, now.plusSeconds(3600),
                        new GeoPoint(37.01, 127), 5, 100)).toList(), 5, now.plusSeconds(1500), List.of());
    }

    @Test
    void sameDropoffSharesOneRouteAndPayerFareStaysSolo() {
        when(routes.routeAt(any(), anyList(), any())).thenReturn(new RouteClient.Route(5, 100, 10000));
        var verifier = new RouteGroupVerifier(routes, new HashMap<>(), now);
        Candidate c = sameDropoff(3);
        assertThat(verifier.verify(c)).isEqualTo(Verdict.PASS);
        verify(routes, times(4)).routeAt(any(), anyList(), eq(c.departure()));
        assertThat(verifier.routesOf(c.order()).total()).isEqualTo(10000);
    }

    @Test
    void sameDropoffLegNeedsNoCall() {
        var requests = sameDropoff(2).order();
        var store = new RoadLegStore(routes);
        store.prepare(requests, now);
        assertThat(store.provider().leg(requests.get(0), requests.get(1))).contains(new RoadLeg(0, 0));
        verifyNoInteractions(routes);
    }

    @Test
    void fourRidersRequireOnlySevenCalls() {
        // 전체 경로 1회와 사람별 단독 시간 4회로 줄었다.
        when(routes.routeAt(any(), anyList(), any())).thenAnswer(call -> {
            List<GeoPoint> stops = call.getArgument(1);
            return new RouteClient.Route(5, stops.size() * 100, 10000, List.of(), Collections.nCopies(stops.size(), 100));
        });
        Candidate c = candidate(4);
        assertThat(new RouteGroupVerifier(routes, new HashMap<>(), now).verify(c)).isEqualTo(Verdict.PASS);
        verify(routes, times(5)).routeAt(any(), anyList(), eq(c.departure()));
    }

    @Test
    void walkingIsIncludedInDelayAndFailureIsCached() {
        quote(10000, 1000, 1200);
        Candidate base = candidate(3);
        GeoPoint middle = base.order().get(1).own();
        when(routes.routeAt(eq(hub.location()), eq(List.of(middle)), any())).thenReturn(new RouteClient.Route(5, 100, 10000));
        List<MatchRequest> order = new ArrayList<>(base.order());
        order.set(1, order.get(1).atPoint(1, 2, 100));
        Candidate c = new Candidate(order, 10, base.departure(), List.of());
        var cache = new HashMap<List<Long>, Instant>();
        var verifier = new RouteGroupVerifier(routes, cache, now);
        assertThat(verifier.check(c).verdict()).isEqualTo(Verdict.FAIL);
        assertThat(verifier.recentlyRejected(c)).isTrue();
    }

    @Test
    void unavailableRouteIsNotCachedAsRejected() {
        when(routes.routeAt(any(), anyList(), any())).thenThrow(new RouteClient.RouteUnavailableException("모의 장애", null));
        var verifier = new RouteGroupVerifier(routes, new HashMap<>(), now);
        assertThat(verifier.check(candidate(3)).verdict()).isEqualTo(Verdict.UNAVAILABLE);
        assertThat(verifier.recentlyRejected(candidate(3))).isFalse();
    }
}
