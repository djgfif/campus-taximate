package com.campustaximate.matching;

import java.time.Instant;

final class Fixtures {

    static final GeoPoint ORIGIN = new GeoPoint(37.0, 127.0);
    static final Hub HUB = new Hub(1, ORIGIN);
    static final Instant SLOT = Instant.parse("2026-11-20T08:30:00Z");

    private Fixtures() {
    }

    /** 거점에서 동쪽 east km, 북쪽 north km 떨어진 지점. */
    static GeoPoint at(double east, double north) {
        double lat = ORIGIN.lat() + north / 111.195;
        double lon = ORIGIN.lon() + east / (111.195 * Math.cos(Math.toRadians(ORIGIN.lat())));
        return new GeoPoint(lat, lon);
    }

    static GeoPoint bearing(double degrees, double km) {
        double rad = Math.toRadians(degrees);
        return at(km * Math.sin(rad), km * Math.cos(rad));
    }

    /** SLOT + 30분에 출발할 수 있고, 끝 시각이 그 endMinutes 분 뒤인 요청. */
    static MatchRequest request(long id, GeoPoint dropoff, int endMinutes) {
        Instant departure = SLOT.plusSeconds(30 * 60);
        return new MatchRequest(id, id + 100, HUB, Gender.FEMALE, departure.minusSeconds(600),
                departure.plusSeconds(endMinutes * 60L), dropoff);
    }

    static MatchRequest request(long id, GeoPoint dropoff) {
        return request(id, dropoff, 30);
    }
}
