package com.campustaximate.server.route;

import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.RoadLeg;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 카카오 키가 없을 때 시연에서만 쓰는 모의 값. 실제 요금이 아니다. */
public class SimulatedRouteClient implements RouteClient {

    private static final double ROAD_FACTOR = 1.3;
    private static final double SPEED_KMH = 24.0;

    @Override
    public Route route(GeoPoint origin, List<GeoPoint> stops) {
        double km = 0;
        GeoPoint previous = origin;
        for (GeoPoint stop : stops) {
            km += previous.distanceKm(stop) * ROAD_FACTOR;
            previous = stop;
        }
        int seconds = (int) Math.round(km / SPEED_KMH * 3600);
        long fare = km <= 1.6 ? 4800 : 4800 + (long) Math.ceil((km - 1.6) / 0.131) * 100;
        List<GeoPoint> coordinates = new java.util.ArrayList<>();
        coordinates.add(origin);
        coordinates.addAll(stops);
        return new Route(km, seconds, fare, coordinates);
    }

    @Override
    public Map<String, RoadLeg> legs(GeoPoint origin, Map<String, GeoPoint> destinations) {
        Map<String, RoadLeg> result = new LinkedHashMap<>();
        destinations.forEach((key, point) -> {
            double km = origin.distanceKm(point) * ROAD_FACTOR;
            result.put(key, new RoadLeg(km, (int) Math.round(km / SPEED_KMH * 3600)));
        });
        return result;
    }

    @Override
    public boolean simulated() {
        return true;
    }
}
