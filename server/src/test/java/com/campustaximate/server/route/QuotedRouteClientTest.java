package com.campustaximate.server.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.RoadLeg;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 확정 분담금(S43) 견적: 혼자 가는 경로는 bias만, 일행 경로는 bias + 여유분. */
class QuotedRouteClientTest {
    private static final GeoPoint HUB = new GeoPoint(37.87, 127.15);
    private static final GeoPoint A = new GeoPoint(37.80, 127.10);
    private static final GeoPoint B = new GeoPoint(37.75, 127.05);

    /** 요금 10,000원 고정, 출발 시각을 기록하는 가짜 경로. */
    private static final class Fixed implements RouteClient {
        final List<Instant> departures = new ArrayList<>();
        public Route route(GeoPoint origin, List<GeoPoint> stops) { return new Route(10, 900, 10000); }
        public Route routeAt(GeoPoint origin, List<GeoPoint> stops, Instant departure) { departures.add(departure); return route(origin, stops); }
        public Map<String, RoadLeg> legs(GeoPoint origin, Map<String, GeoPoint> destinations) { return Map.of(); }
        public boolean simulated() { return true; }
    }

    @Test
    void soloRouteGetsBiasOnlyAndSharedRouteAlsoGetsMargin() {
        var quoted = new QuotedRouteClient(new Fixed(), 0.0657, 0.01);
        assertThat(quoted.route(HUB, List.of(A)).fare()).isEqualTo(10657);
        assertThat(quoted.route(HUB, List.of(A, B)).fare()).isEqualTo(10757);
        // 같은 곳에 둘이 내려도 일행 경로다.
        assertThat(quoted.route(HUB, List.of(A, A)).fare()).isEqualTo(10757);
        // 목적지가 1곳으로 합쳐진 뒤에도 sharedRouteAt은 일행 여유분(margin)을 적용한다.
        assertThat(quoted.sharedRouteAt(HUB, List.of(A), Instant.parse("2026-11-20T08:00:00Z")).fare()).isEqualTo(10757);
    }

    @Test
    void departureTimeIsPassedThroughAndDistanceTimeAreUntouched() {
        var raw = new Fixed();
        var quoted = new QuotedRouteClient(raw, 0.0657, 0.01);
        Instant at = Instant.parse("2026-11-20T08:00:00Z");
        var route = quoted.routeAt(HUB, List.of(A, B), at);
        assertThat(raw.departures).containsExactly(at);
        assertThat(route.km()).isEqualTo(10);
        assertThat(route.seconds()).isEqualTo(900);
    }

    @Test
    void zeroFactorsKeepQuotesAndMarginKeepsTheSavingRuleHonest() {
        assertThat(new QuotedRouteClient(new Fixed(), 0, 0).route(HUB, List.of(A, B)).fare()).isEqualTo(10000);
        // 혼자 각자 10,000원인 두 사람이 17,900원 경로로 묶이는 경우. bias는 양쪽에 같이 붙어 비교를 바꾸지 않는다:
        // 여유분 없이 17,900×1.0657 = 19,076 ≤ (10,657×2)×0.9 = 19,182.6 → 통과,
        // 여유분 1%를 더하면 17,900×1.0757 = 19,255 > 19,182.6 → 탈락. 기준은 여유분만큼만 엄격해진다.
        long solo = QuotedRouteClient.solo(10000, 0.0657) * 2;
        assertThat(QuotedRouteClient.shared(17900, 0.0657, 0) * 10 <= solo * 9).isTrue();
        assertThat(QuotedRouteClient.shared(17900, 0.0657, 0.01) * 10 > solo * 9).isTrue();
    }

    @Test
    void rejectsInvalidFactors() {
        assertThatThrownBy(() -> new QuotedRouteClient(new Fixed(), -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotedRouteClient(new Fixed(), 0, -0.01)).isInstanceOf(IllegalArgumentException.class);
    }
}
