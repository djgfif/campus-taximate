package com.campustaximate.server.service;

import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.TripPath;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.route.RouteClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 요청 전 단독 경로 조회. 입력이 바뀔 때마다 카카오를 부르지 않도록 같은 조회는 10분간 재사용하고,
 * 미리보기는 회원마다 분당 호출 수를 제한한다. 일행 확정의 값은 확인 단계에서 다시 조회하므로 잠깐 묵어도 된다.
 */
@Component
@RequiredArgsConstructor
public class SoloRouteLookup {

    static final int PREVIEW_LIMIT_PER_MINUTE = 20;
    private static final Duration KEEP = Duration.ofMinutes(10);
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private record Key(GeoPoint origin, List<GeoPoint> stops, Instant departure) {
    }

    private record Entry(RouteClient.Route route, Instant expiresAt) {
    }

    private final RouteClient routes;
    private final AppClock clock;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final Map<Long, Deque<Instant>> calls = new ConcurrentHashMap<>();

    public RouteClient.Route route(TripPath path, Instant departure) {
        Instant now = clock.now();
        entries.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
        Key key = new Key(path.origin(), path.stops(), departure);
        Entry cached = entries.get(key);
        if (cached != null) {
            return cached.route();
        }
        RouteClient.Route route = routes.routeAt(path.origin(), path.stops(), departure);
        entries.put(key, new Entry(route, now.plus(KEEP)));
        return route;
    }

    /** 이미 조회한 값은 횟수에 넣지 않는다. */
    public RouteClient.Route preview(long memberId, TripPath path, Instant departure) {
        Instant now = clock.now();
        Key key = new Key(path.origin(), path.stops(), departure);
        Entry cached = entries.get(key);
        if (cached == null || !cached.expiresAt().isAfter(now)) {
            countCall(memberId, now);
        }
        return route(path, departure);
    }

    private void countCall(long memberId, Instant now) {
        Deque<Instant> recent = calls.computeIfAbsent(memberId, id -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && !recent.peekFirst().plus(WINDOW).isAfter(now)) {
                recent.pollFirst();
            }
            if (recent.size() >= PREVIEW_LIMIT_PER_MINUTE) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_PREVIEWS", "조회가 너무 많습니다. 잠시 뒤 다시 시도해 주세요.");
            }
            recent.addLast(now);
        }
    }
}
