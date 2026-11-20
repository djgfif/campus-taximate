package com.campustaximate.server.route;

import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.RoadLeg;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 확정 분담금(S43)의 견적. 카카오 예상 요금은 실제 미터보다 평균적으로 낮아서(TIMS 2025, +6.57%)
 * 모든 요금에 (1 + bias)를 곱해 "실제로 낼 것으로 기대되는 요금"으로 쓴다.
 * 여러 곳에 내리는 일행 경로(정차 2곳 이상)에는 정산 적립금을 쌓는 여유분 margin을 더한다.
 * 혼자 가는 경로(정차 1곳)는 여유분 없이 bias만 적용하므로, 절약 기준(혼자보다 10% 이상)은 여유분만큼만 엄격해진다.
 */
public final class QuotedRouteClient implements RouteClient {
    private final RouteClient delegate;
    private final double bias;
    private final double margin;

    public QuotedRouteClient(RouteClient delegate, double bias, double margin) {
        if (bias <= -1 || margin < 0 || !Double.isFinite(bias) || !Double.isFinite(margin)) {
            throw new IllegalArgumentException("견적 보정 계수가 올바르지 않습니다.");
        }
        this.delegate = delegate;
        this.bias = bias;
        this.margin = margin;
    }

    /** 혼자 가는 견적: 기대 미터 요금. */
    public static long solo(long fare, double bias) {
        return Math.max(1, Math.round(fare * (1 + bias)));
    }

    /** 일행 확정 금액: 기대 미터 요금 + 적립 여유분. */
    public static long shared(long fare, double bias, double margin) {
        return Math.max(1, Math.round(fare * (1 + bias + margin)));
    }

    private Route quote(Route route, List<GeoPoint> stops) {
        long fare = stops.size() > 1 ? shared(route.fare(), bias, margin) : solo(route.fare(), bias);
        return route.withFare(fare);
    }

    @Override
    public Route route(GeoPoint origin, List<GeoPoint> stops) {
        return quote(delegate.route(origin, stops), stops);
    }

    @Override
    public Route routeAt(GeoPoint origin, List<GeoPoint> stops, Instant departure) {
        return quote(delegate.routeAt(origin, stops, departure), stops);
    }

    @Override
    public Map<String, RoadLeg> legs(GeoPoint origin, Map<String, GeoPoint> destinations) {
        return delegate.legs(origin, destinations);
    }

    @Override
    public boolean simulated() {
        return delegate.simulated();
    }
}
