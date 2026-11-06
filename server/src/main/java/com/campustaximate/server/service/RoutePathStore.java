package com.campustaximate.server.service;

import com.campustaximate.matching.GeoPoint;
import com.campustaximate.server.route.RouteClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 경로 좌표만 30분간 보관한다. */
@Component
@RequiredArgsConstructor
public class RoutePathStore {
    private static final Duration KEEP = Duration.ofMinutes(30);
    private final RouteClient routes;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    private record Key(GeoPoint origin, List<GeoPoint> stops) {
    }

    private record Entry(List<GeoPoint> coordinates, Instant expiresAt) {
    }

    public void remember(GeoPoint origin, List<GeoPoint> stops, RouteClient.Route route) {
        entries.put(new Key(origin, List.copyOf(stops)), new Entry(route.coordinates(), Instant.now().plus(KEEP)));
    }

    public List<GeoPoint> coordinates(GeoPoint origin, List<GeoPoint> stops) {
        purge();
        Entry entry = entries.get(new Key(origin, stops));
        if (entry != null) {
            return entry.coordinates();
        }
        RouteClient.Route route = routes.route(origin, stops);
        remember(origin, stops, route);
        return route.coordinates();
    }

    @Scheduled(fixedDelay = 60_000)
    public void purge() {
        Instant now = Instant.now();
        entries.values().removeIf(e -> !e.expiresAt().isAfter(now));
    }
}
