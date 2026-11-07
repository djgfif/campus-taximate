package com.campustaximate.server.service;

import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.LegProvider;
import com.campustaximate.matching.MatchRequest;
import com.campustaximate.matching.RoadLeg;
import com.campustaximate.matching.TripPlan;
import com.campustaximate.server.route.RouteClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 개인 지점 사이의 도로 값을 메모리에 30분만 둔다. DB에 저장하지 않는다.
 * 다중 목적지 쿼터가 작아서 이미 있는 구간은 다시 부르지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoadLegStore {

    private static final Duration KEEP = Duration.ofMinutes(30);
    private static final double RADIUS_KM = 10.0;
    private static final int MAX_DESTINATIONS = 30;

    private record Key(long from, long to) {
    }

    private record Entry(RoadLeg leg, Instant expiresAt) {
    }

    private final RouteClient routes;
    private final Map<Key, Entry> legs = new ConcurrentHashMap<>();

    /** 슬롯 대상 요청끼리 없는 구간을 채운다. 승하차 순서의 앞 사람에서 뒤 사람으로 가는 방향만 부른다. */
    public void prepare(List<MatchRequest> requests, Instant now) {
        legs.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
        for (MatchRequest from : requests) {
            Map<String, GeoPoint> targets = new LinkedHashMap<>();
            for (MatchRequest to : requests) {
                if (needsLeg(from, to, now) && targets.size() < MAX_DESTINATIONS) {
                    targets.put(String.valueOf(to.id()), to.own());
                }
            }
            if (!targets.isEmpty()) {
                fetch(from, targets, now);
            }
        }
    }

    public LegProvider provider() {
        return (from, to) -> from.own().isSameStop(to.own())
                ? Optional.of(new RoadLeg(0, 0))
                : Optional.ofNullable(legs.get(new Key(from.id(), to.id()))).map(Entry::leg);
    }

    private boolean needsLeg(MatchRequest from, MatchRequest to, Instant now) {
        return from.id() != to.id()
                && from.poolKey().equals(to.poolKey())
                && TripPlan.of(from.direction()).comparator().compare(from, to) < 0
                && !from.own().isSameStop(to.own())
                && from.own().distanceKm(to.own()) <= RADIUS_KM
                && !legs.containsKey(new Key(from.id(), to.id()));
    }

    private void fetch(MatchRequest from, Map<String, GeoPoint> targets, Instant now) {
        try {
            routes.legs(from.own(), targets).forEach((key, leg) ->
                    legs.put(new Key(from.id(), Long.parseLong(key)), new Entry(leg, now.plus(KEEP))));
        } catch (RouteClient.RouteUnavailableException e) {
            log.warn("구간 조회 실패. 다음 슬롯에서 다시 부릅니다: {}", e.getMessage());
        }
    }
}
