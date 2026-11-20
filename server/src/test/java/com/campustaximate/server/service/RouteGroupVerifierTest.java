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
        var cache = new HashMap<RouteGroupVerifier.RejectionKey, Instant>();
        var verifier = new RouteGroupVerifier(routes, cache, now);
        assertThat(verifier.check(c).verdict()).isEqualTo(Verdict.FAIL);
        assertThat(verifier.recentlyRejected(c)).isTrue();
    }

    /** 실제 카카오 경유지 응답은 구간 합이 요약 시간보다 8~11초 작게 온다(2118초 요약, 구간 합 2110초 등). */
    private void quoteWithResidual(int summarySeconds, List<Integer> legs) {
        when(routes.routeAt(any(), anyList(), any())).thenAnswer(call -> {
            List<GeoPoint> stops = call.getArgument(1);
            if (stops.size() == 1) return new RouteClient.Route(5, 1200, 10000, List.of(), List.of(1200));
            return new RouteClient.Route(5, summarySeconds, 10000, List.of(), legs);
        });
    }

    @Test
    void smallKakaoResidualBetweenSectionsAndSummaryStillVerifies() {
        quoteWithResidual(1210, List.of(100, 500, 600));
        var verifier = new RouteGroupVerifier(routes, new HashMap<>(), now);
        Candidate c = candidate(3);
        assertThat(verifier.verify(c)).isEqualTo(Verdict.PASS);
        // 남는 10초를 구간에 나눠 마지막 하차자의 누적이 요약 시간과 같다.
        assertThat(verifier.routesOf(c.order()).rideSeconds().getLast()).isEqualTo(1210);
    }

    @Test
    void largeResidualStaysUnavailable() {
        quoteWithResidual(1500, List.of(100, 500, 600));
        var verifier = new RouteGroupVerifier(routes, new HashMap<>(), now);
        assertThat(verifier.check(candidate(3)).verdict()).isEqualTo(Verdict.UNAVAILABLE);
    }

    @Test
    void residualIsSpreadInProportionToSectionLength() {
        assertThat(RouteGroupVerifier.reconcileLegs(List.of(100, 500, 600), 1210)).containsExactly(101, 504, 605);
        assertThat(RouteGroupVerifier.reconcileLegs(List.of(100, 500, 600), 1192)).containsExactly(99, 497, 596);
        assertThat(RouteGroupVerifier.reconcileLegs(List.of(100, 500, 600), 1200)).containsExactly(100, 500, 600);
        assertThat(RouteGroupVerifier.reconcileLegs(List.of(100, 500, 600), 1300)).isNull();
        assertThat(RouteGroupVerifier.reconcileLegs(List.of(0, 0), 5)).isNull();
        assertThat(RouteGroupVerifier.reconcileLegs(List.of(100, -1), 99)).isNull();
    }

    @Test
    void sameDestinationPartyStillGetsQuotedMargin() {
        var raw = new RouteClient() {
            public Route route(GeoPoint origin, List<GeoPoint> stops) { return new Route(5, 600, 10000, List.of(), List.of(600)); }
            public java.util.Map<String, com.campustaximate.matching.RoadLeg> legs(GeoPoint o, java.util.Map<String, GeoPoint> d) { return java.util.Map.of(); }
            public boolean simulated() { return true; }
        };
        var quoted = new com.campustaximate.server.route.QuotedRouteClient(raw, 0.0657, 0.01);
        GeoPoint sameStop = new GeoPoint(37.05, 127.0);
        Instant departure = now.plusSeconds(3600);
        List<MatchRequest> order = List.of(
                new MatchRequest(1, 1, hub, Gender.FEMALE, departure, departure.plusSeconds(1800), sameStop, 5, 600),
                new MatchRequest(2, 2, hub, Gender.FEMALE, departure, departure.plusSeconds(1800), sameStop, 5, 600));
        Candidate c = new Candidate(order, 10, departure, List.of());
        var verifier = new RouteGroupVerifier(quoted, new HashMap<>(), now);
        assertThat(verifier.verify(c)).isEqualTo(Verdict.PASS);
        // GeoPoint.mergeStops로 stops가 1곳으로 합쳐져도 일행 확정 금액은 bias + margin(1%)이 적용된다.
        assertThat(verifier.routesOf(order).total()).isEqualTo(10757);
        assertThat(verifier.routesOf(order).solo()).extracting(RouteClient.Route::fare).containsExactly(10657L, 10657L);
    }

    @Test
    void unavailableRouteIsNotCachedAsRejected() {
        when(routes.routeAt(any(), anyList(), any())).thenThrow(new RouteClient.RouteUnavailableException("모의 장애", null));
        var verifier = new RouteGroupVerifier(routes, new HashMap<>(), now);
        assertThat(verifier.check(candidate(3)).verdict()).isEqualTo(Verdict.UNAVAILABLE);
        assertThat(verifier.recentlyRejected(candidate(3))).isFalse();
    }

    @Test
    void failedHubDoesNotPoisonAnotherHubOrDepartureOrOrder() {
        quote(27001, 600, 1200);
        var cache = new HashMap<RouteGroupVerifier.RejectionKey, Instant>();
        var verifier = new RouteGroupVerifier(routes, cache, now);
        Candidate a = candidate(3);
        assertThat(verifier.check(a).verdict()).isEqualTo(Verdict.FAIL);
        assertThat(verifier.recentlyRejected(a)).isTrue();
        Hub other = new Hub(2, new GeoPoint(37.005, 127));
        Candidate b = new Candidate(a.order().stream().map(r -> new MatchRequest(r.id(), r.memberId(), other,
                r.gender(), r.windowStart(), r.windowEnd(), r.own(), r.soloKm(), r.soloSeconds())).toList(),
                10, a.departure(), List.of());
        assertThat(verifier.recentlyRejected(b)).isFalse();
        quote(20000, 600, 1200);
        assertThat(verifier.check(b).verdict()).isEqualTo(Verdict.PASS);
        Candidate later = new Candidate(a.order(), 10, a.departure().plusSeconds(60), List.of());
        assertThat(verifier.recentlyRejected(later)).isFalse();
        assertThat(verifier.check(later).verdict()).isEqualTo(Verdict.PASS);
        var reverse = new ArrayList<>(a.order());
        Collections.reverse(reverse);
        assertThat(verifier.recentlyRejected(new Candidate(reverse, 10, a.departure(), List.of()))).isFalse();
        assertThat(new RouteGroupVerifier(routes, cache, now.plusSeconds(60)).recentlyRejected(a)).isFalse();
    }
}
