package com.campustaximate.matching;

import java.time.Instant;
import java.util.Optional;

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

    private static final double ROAD = 1.3;
    private static final double SPEED_KMH = 24.0;

    private static int seconds(double km) {
        return (int) Math.round(km / SPEED_KMH * 3600);
    }

    /** 테스트용 도로 값. 직선 거리의 1.3배, 시속 24km. */
    static final LegProvider LEGS = (from, to) -> {
        double km = from.own().distanceKm(to.own()) * ROAD;
        return Optional.of(new RoadLeg(km, seconds(km)));
    };

    static MatchRequest of(long id, long memberId, Hub hub, Gender gender, Instant start, Instant end, GeoPoint dropoff) {
        double km = hub.location().distanceKm(dropoff) * ROAD;
        return new MatchRequest(id, memberId, hub, gender, start, end, dropoff, km, seconds(km));
    }

    /** SLOT + 30분에 출발할 수 있고, 끝 시각이 그 endMinutes 분 뒤인 요청. */
    static MatchRequest request(long id, GeoPoint dropoff, int endMinutes) {
        Instant departure = SLOT.plusSeconds(30 * 60);
        return of(id, id + 100, HUB, Gender.FEMALE, departure.minusSeconds(600),
                departure.plusSeconds(endMinutes * 60L), dropoff);
    }

    static MatchRequest request(long id, GeoPoint dropoff) {
        return request(id, dropoff, 30);
    }
}
