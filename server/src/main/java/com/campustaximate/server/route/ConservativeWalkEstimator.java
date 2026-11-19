package com.campustaximate.server.route;

import com.campustaximate.matching.GeoPoint;


/**
 * 직선거리의 1.3배, 분당 67m, 추가 여유 1분.
 * TMAP 키가 없는 개발·테스트 환경에서만 쓴다. 키가 있는 운영에서 TMAP이 실패하면
 * 이 추정으로 대신하지 않는다(TmapWalkClient가 WalkUnavailableException으로 거절).
 * 예외: 출발지가 승차 지점에서 10m 이내면 TMAP이 좌표를 거절하므로 이 추정(1분)을 쓴다.
 */
public class ConservativeWalkEstimator implements WalkEstimator {
    @Override
    public Walk estimate(GeoPoint origin, GeoPoint point) {
        int minutes = (int) Math.ceil(origin.distanceKm(point) * 1000 * 1.3 / 67) + 1;
        return new Walk(minutes * 60, true);
    }
}
