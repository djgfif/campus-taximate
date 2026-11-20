package com.campustaximate.server.route;

import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.RoadLeg;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 거점에서 stops를 차례로 거치는 경로의 거리, 시간, 택시 요금을 구한다. 마지막 stop이 목적지다. */
public interface RouteClient {

    Route route(GeoPoint origin, List<GeoPoint> stops);

    /** departure에 출발할 때의 경로. departure가 없으면 지금 기준으로 조회한다. */
    default Route routeAt(GeoPoint origin, List<GeoPoint> stops, Instant departure) {
        return route(origin, stops);
    }

    /**
     * 여럿이 함께 타는 일행 경로. 같은 곳에 내려 정차가 하나로 합쳐져도 일행 요금으로 다룬다.
     * 기본은 routeAt과 같고, 확정 분담금 견적(QuotedRouteClient)만 일행 여유분을 더한다.
     * 일행 경로는 반드시 이 메서드로 조회한다. 구현 클래스를 확인해 분기하면 감싸는 층이 생길 때 여유분이 조용히 빠진다.
     */
    default Route sharedRouteAt(GeoPoint origin, List<GeoPoint> stops, Instant departure) {
        return routeAt(origin, stops, departure);
    }

    /** 한 지점에서 여러 지점까지의 거리와 시간. 응답에 없는 목적지는 결과에서 빠진다. */
    Map<String, RoadLeg> legs(GeoPoint origin, Map<String, GeoPoint> destinations);

    /** 카카오가 아닌 모의 값이면 true. 화면에 표시한다. */
    boolean simulated();

    /** legSeconds는 정차 지점 사이 구간별 시간이다. */
    record Route(double km, int seconds, long fare, List<GeoPoint> coordinates, List<Integer> legSeconds) {
        public Route(double km, int seconds, long fare) {
            this(km, seconds, fare, List.of(), List.of());
        }

        public Route(double km, int seconds, long fare, List<GeoPoint> coordinates) {
            this(km, seconds, fare, coordinates, List.of());
        }

        public Route withFare(long fixedFare) {
            return new Route(km, seconds, fixedFare, coordinates, legSeconds);
        }

        public Route {
            coordinates = List.copyOf(coordinates);
            legSeconds = List.copyOf(legSeconds);
        }
    }

    class RouteUnavailableException extends RuntimeException {
        public RouteUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
