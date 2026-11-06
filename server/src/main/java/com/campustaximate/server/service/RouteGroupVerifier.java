package com.campustaximate.server.service;

import com.campustaximate.matching.Candidate;
import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.GroupVerifier;
import com.campustaximate.matching.MatchRequest;
import com.campustaximate.matching.Verdict;
import com.campustaximate.server.route.RouteClient;
import com.campustaximate.settlement.SettlementCalculator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 뽑힌 조합의 누적 값과 각자의 단독 값을 같은 시점에 조회해 15분, 10% 조건을 다시 검사한다. 슬롯 한 번만 쓴다. */
final class RouteGroupVerifier implements GroupVerifier {

    private static final int MAX_DELAY_SECONDS = 15 * 60;
    private static final double MIN_SAVING_RATE = 0.10;
    private static final Duration REJECT_KEEP = Duration.ofMinutes(30);

    private final RouteClient routes;
    private final Map<List<Long>, Instant> rejected;
    private final Instant now;
    private final Map<List<Long>, VerifiedRoutes> verified = new HashMap<>();
    private final RoutePathStore paths;

    record VerifiedRoutes(List<RouteClient.Route> cumulative, List<RouteClient.Route> solo) {
    }

    RouteGroupVerifier(RouteClient routes, Map<List<Long>, Instant> rejected, Instant now) {
        this(routes, rejected, now, null);
    }

    RouteGroupVerifier(RouteClient routes, Map<List<Long>, Instant> rejected, Instant now, RoutePathStore paths) {
        this.paths = paths;
        this.routes = routes;
        this.rejected = rejected;
        this.now = now;
    }

    @Override
    public boolean recentlyRejected(Candidate candidate) {
        Instant until = rejected.get(candidate.sortedIds());
        return until != null && now.isBefore(until);
    }

    private Verdict reject(Candidate candidate) {
        rejected.put(candidate.sortedIds(), now.plus(REJECT_KEEP));
        return Verdict.FAIL;
    }

    /** 통과한 조합의 같은 시점 경로 값. */
    VerifiedRoutes routesOf(List<MatchRequest> order) {
        return verified.get(order.stream().map(MatchRequest::id).toList());
    }

    @Override
    public Verdict verify(Candidate candidate) {
        List<MatchRequest> order = candidate.dropoffOrder();
        GeoPoint hub = order.get(0).hub().location();
        List<GeoPoint> stops = new ArrayList<>();
        List<RouteClient.Route> cumulative = new ArrayList<>();
        List<Integer> seconds = new ArrayList<>();
        List<RouteClient.Route> solo = new ArrayList<>();
        try {
            for (MatchRequest rider : order) {
                boolean sameStop = !stops.isEmpty() && stops.getLast().isSameStop(rider.dropoff());
                stops.add(rider.dropoff());
                RouteClient.Route route = sameStop ? cumulative.getLast() : routes.route(hub, List.copyOf(stops));
                cumulative.add(route);
                seconds.add(route.seconds());
                RouteClient.Route alone = stops.size() == 1 ? route : routes.route(hub, List.of(rider.dropoff()));
                solo.add(alone);
                if (paths != null) {
                    paths.remember(hub, stops, route);
                    paths.remember(hub, List.of(rider.dropoff()), alone);
                }
            }
        } catch (RouteClient.RouteUnavailableException e) {
            return Verdict.UNAVAILABLE;
        }
        for (int i = 1; i < order.size(); i++) {
            if (seconds.get(i) < seconds.get(i - 1)) {
                return reject(candidate);
            }
        }
        long total = cumulative.get(cumulative.size() - 1).fare();
        long soloTotal = solo.stream().mapToLong(RouteClient.Route::fare).sum();
        if (total > soloTotal * (1 - MIN_SAVING_RATE)) {
            return reject(candidate);
        }
        for (int i = 0; i < order.size(); i++) {
            if (seconds.get(i) - solo.get(i).seconds() > MAX_DELAY_SECONDS) {
                return reject(candidate);
            }
        }
        try {
            SettlementCalculator.shares(total,
                    SettlementCalculator.ratioUnits(solo.stream().map(RouteClient.Route::fare).toList()));
        } catch (IllegalArgumentException | SettlementCalculator.NegativeShareException e) {
            return reject(candidate);
        }
        verified.put(order.stream().map(MatchRequest::id).toList(),
                new VerifiedRoutes(List.copyOf(cumulative), List.copyOf(solo)));
        return Verdict.PASS;
    }
}
