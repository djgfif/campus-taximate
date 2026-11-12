package com.campustaximate.server.route;

import com.campustaximate.matching.GeoPoint;


/** 직선거리의 1.3배, 분당 67m, 추가 여유 1분. */
public class ConservativeWalkEstimator implements WalkEstimator {
    @Override
    public Walk estimate(GeoPoint origin, GeoPoint point) {
        int minutes = (int) Math.ceil(origin.distanceKm(point) * 1000 * 1.3 / 67) + 1;
        return new Walk(minutes * 60, true);
    }
}
