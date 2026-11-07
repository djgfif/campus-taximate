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
