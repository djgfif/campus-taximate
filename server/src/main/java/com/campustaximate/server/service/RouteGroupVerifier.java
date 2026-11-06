package com.campustaximate.server.service;

import com.campustaximate.matching.Candidate;
import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.GroupVerifier;
import com.campustaximate.matching.MatchRequest;
import com.campustaximate.matching.Verdict;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.route.RouteClient;
import com.campustaximate.settlement.SettlementCalculator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 뽑힌 조합의 누적 시간과 요금을 조회해 15분, 10% 조건을 다시 검사한다. 슬롯 한 번만 쓴다. */
final class RouteGroupVerifier implements GroupVerifier {

    private static final int MAX_DELAY_SECONDS = 15 * 60;
    private static final double MIN_SAVING_RATE = 0.10;

    private final RouteClient routes;
    private final Map<Long, RideRequest> snapshot;
    private final Map<List<Long>, List<Long>> cumulativeFares = new HashMap<>();

    RouteGroupVerifier(RouteClient routes, Map<Long, RideRequest> snapshot) {
        this.routes = routes;
        this.snapshot = snapshot;
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
        try {
            for (MatchRequest rider : order) {
                stops.add(rider.dropoff());
                RouteClient.Route route = routes.route(hub, List.copyOf(stops));
                fares.add(route.fare());
                seconds.add(route.seconds());
            }
        } catch (RouteClient.RouteUnavailableException e) {
            return Verdict.UNAVAILABLE;
        }
        for (int i = 1; i < order.size(); i++) {
            if (fares.get(i) < fares.get(i - 1) || seconds.get(i) < seconds.get(i - 1)) {
                return Verdict.FAIL;
            }
        }
        List<Long> units = SettlementCalculator.ratioUnits(fares);
        for (int i = 0; i < order.size(); i++) {
            RideRequest rider = snapshot.get(order.get(i).id());
            double shareWon = units.get(i) / (double) SettlementCalculator.UNITS_PER_WON;
            if (seconds.get(i) - rider.getSoloSeconds() > MAX_DELAY_SECONDS
                    || shareWon > rider.getSoloFare() * (1 - MIN_SAVING_RATE)) {
                return Verdict.FAIL;
            }
        }
        cumulativeFares.put(order.stream().map(MatchRequest::id).toList(), fares);
        return Verdict.PASS;
    }
}
