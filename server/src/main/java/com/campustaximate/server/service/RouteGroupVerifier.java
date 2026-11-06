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

/** 뽑힌 조합의 누적 값과 각자의 혼자 값을 같은 시점에 조회해 15분, 10% 조건을 다시 검사한다. 슬롯 한 번만 쓴다. */
final class RouteGroupVerifier implements GroupVerifier {

    private static final int MAX_DELAY_SECONDS = 15 * 60;
    private static final double MIN_SAVING_RATE = 0.10;
    private static final Duration REJECT_KEEP = Duration.ofMinutes(30);

    private final RouteClient routes;
    private final Map<List<Long>, Instant> rejected;
    private final Instant now;
    private final Map<List<Long>, List<Long>> cumulativeFares = new HashMap<>();

    RouteGroupVerifier(RouteClient routes, Map<List<Long>, Instant> rejected, Instant now) {
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

    /** 통과한 조합의 누적 요금. 하차 순서와 같다. */
    List<Long> faresOf(List<MatchRequest> order) {
        return cumulativeFares.get(order.stream().map(MatchRequest::id).toList());
    }

    @Override
    public Verdict verify(Candidate candidate) {
        List<MatchRequest> order = candidate.dropoffOrder();
        GeoPoint hub = order.get(0).hub().location();
        List<GeoPoint> stops = new ArrayList<>();
        List<Long> fares = new ArrayList<>();
        List<Integer> seconds = new ArrayList<>();
        List<RouteClient.Route> solo = new ArrayList<>();
        try {
            for (MatchRequest rider : order) {
                stops.add(rider.dropoff());
                RouteClient.Route route = routes.route(hub, List.copyOf(stops));
                fares.add(route.fare());
                seconds.add(route.seconds());
                solo.add(stops.size() == 1 ? route : routes.route(hub, List.of(rider.dropoff())));
            }
        } catch (RouteClient.RouteUnavailableException e) {
            return Verdict.UNAVAILABLE;
        }
        for (int i = 1; i < order.size(); i++) {
            if (fares.get(i) < fares.get(i - 1) || seconds.get(i) < seconds.get(i - 1)) {
                return reject(candidate);
            }
        }
        List<Long> units = SettlementCalculator.ratioUnits(fares);
        for (int i = 0; i < order.size(); i++) {
            double shareWon = units.get(i) / (double) SettlementCalculator.UNITS_PER_WON;
            if (seconds.get(i) - solo.get(i).seconds() > MAX_DELAY_SECONDS
                    || shareWon > solo.get(i).fare() * (1 - MIN_SAVING_RATE)) {
                return reject(candidate);
            }
        }
        cumulativeFares.put(order.stream().map(MatchRequest::id).toList(), fares);
        return Verdict.PASS;
    }
}
