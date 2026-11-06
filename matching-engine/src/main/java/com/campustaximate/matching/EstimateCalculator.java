package com.campustaximate.matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 카카오 호출 없이 거리로 15분, 10% 조건을 거른다. */
public final class EstimateCalculator {

    private final MatchingSettings settings;

    public EstimateCalculator(MatchingSettings settings) {
        this.settings = settings;
    }

    /** 가까운 곳부터 내리는 순서로 시험해 조건을 통과하면 조합을 돌려준다. */
    public Optional<Candidate> evaluate(List<MatchRequest> group) {
        GeoPoint hub = group.get(0).hub().location();
        List<MatchRequest> order = new ArrayList<>(group);
        order.sort(Comparator.<MatchRequest>comparingDouble(r -> hub.distanceKm(r.dropoff()))
                .thenComparingLong(MatchRequest::id));

        GeoPoint previous = hub;
        double cumulativeKm = 0;
        double shareKm = 0;
        for (int i = 0; i < order.size(); i++) {
            MatchRequest rider = order.get(i);
            double segmentKm = roadKm(previous, rider.dropoff());
            cumulativeKm += segmentKm;
            shareKm += segmentKm / (order.size() - i);
            double soloKm = roadKm(hub, rider.dropoff());
            double delayMinutes = (cumulativeKm - soloKm) / settings.speedKmh() * 60;
            if (delayMinutes > settings.maxDelayMinutes()
                    || shareKm > soloKm * (1 - settings.minSavingRate())) {
                return Optional.empty();
            }
            previous = rider.dropoff();
        }
        return Optional.of(new Candidate(order, cumulativeKm));
    }

    private double roadKm(GeoPoint from, GeoPoint to) {
        return from.distanceKm(to) * settings.roadFactor();
    }
}
