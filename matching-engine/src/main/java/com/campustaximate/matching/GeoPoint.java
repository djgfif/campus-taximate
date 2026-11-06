package com.campustaximate.matching;

/** 위도와 경도. */
public record GeoPoint(double lat, double lon) {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double SAME_STOP_KM = 0.05;

    /** 50m 안이면 한 번만 정차한다. 같은 곳을 경유지로 넣으면 유턴 경로가 나올 수 있다. */
    public boolean isSameStop(GeoPoint other) {
        return distanceKm(other) <= SAME_STOP_KM;
    }

    /** 두 지점의 직선 거리(km). */
    public double distanceKm(GeoPoint other) {
        double dLat = Math.toRadians(other.lat - lat);
        double dLon = Math.toRadians(other.lon - lon);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(other.lat))
                * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** 이 지점에서 본 상대 지점의 방위각(라디안). */
    public double bearingTo(GeoPoint other) {
        double lat1 = Math.toRadians(lat);
        double lat2 = Math.toRadians(other.lat);
        double dLon = Math.toRadians(other.lon - lon);
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        return Math.atan2(y, x);
    }
}
