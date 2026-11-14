package com.campustaximate.server.route;

import com.campustaximate.matching.GeoPoint;

/** 보행 조회 포트. 다음 단계에서 TMAP 구현으로 교체할 수 있다. */
public interface WalkEstimator {
    record Walk(int seconds, boolean estimated) { }
    Walk estimate(GeoPoint origin, GeoPoint point);
}
