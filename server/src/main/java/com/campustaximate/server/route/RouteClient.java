package com.campustaximate.server.route;

import com.campustaximate.matching.GeoPoint;
import java.util.List;

/** 거점에서 stops를 차례로 거치는 경로의 거리, 시간, 택시 요금을 구한다. 마지막 stop이 목적지다. */
public interface RouteClient {

    Route route(GeoPoint origin, List<GeoPoint> stops);

    /** 카카오가 아닌 모의 값이면 true. 화면에 표시한다. */
    boolean simulated();

    record Route(double km, int seconds, long fare) {
    }

    class RouteUnavailableException extends RuntimeException {
        public RouteUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
