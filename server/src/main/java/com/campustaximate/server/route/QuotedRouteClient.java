package com.campustaximate.server.route;

import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.RoadLeg;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 확정 분담금(S43)의 견적. 모든 요금에 (1 + bias)를 곱해 "실제로 낼 것으로 기대되는 요금"으로 쓴다.
 * 기본 bias는 0(카카오 예상 요금 그대로)이다. 실험 C16에서 카카오 견적은 시계외 할증까지 반영하면 실제 평균보다 이미 조금(약 1~5%) 높아
 * 예전 +6.57%(요금표 공식 대비 실제 차이)를 곱할 근거가 없었다. 운영 중 실제 요금이 쌓이면 설정으로 다시 맞춘다.
 * 일행 경로에는 정산 적립금을 쌓는 여유분 margin을 더한다(목적지가 하나로 합쳐진 일행도 포함).
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

    private Route quote(Route route, boolean shared) {
        long fare = shared ? shared(route.fare(), bias, margin) : solo(route.fare(), bias);
        return route.withFare(fare);
    }

    /** 목적지가 하나로 합쳐져도 여럿이 함께 타는 일행 경로는 여유분(margin)을 적용한다. */
    @Override
    public Route sharedRouteAt(GeoPoint origin, List<GeoPoint> stops, Instant departure) {
        if (stops.size() > 1 || margin == 0) {
            return routeAt(origin, stops, departure);
        }
        return quote(delegate.routeAt(origin, stops, departure), true);
    }

    @Override
    public Route route(GeoPoint origin, List<GeoPoint> stops) {
        return quote(delegate.route(origin, stops), stops.size() > 1);
    }

    @Override
    public Route routeAt(GeoPoint origin, List<GeoPoint> stops, Instant departure) {
        return quote(delegate.routeAt(origin, stops, departure), stops.size() > 1);
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
