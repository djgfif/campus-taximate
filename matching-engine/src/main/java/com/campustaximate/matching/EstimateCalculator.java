package com.campustaximate.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 도로 값으로 15분, 10% 조건을 거른다. 요금 대신 거리로 절약을 본다. */
public final class EstimateCalculator {

    private final MatchingSettings settings;

    public EstimateCalculator(MatchingSettings settings) {
        this.settings = settings;
    }

    /** 방향의 승하차 순서로 시험해 조건을 통과하면 조합을 돌려준다. */
    public Optional<Candidate> evaluate(List<MatchRequest> group, LegProvider legs) {
        TripPlan plan = TripPlan.of(group.get(0).direction(), settings);
        List<MatchRequest> order = plan.order(group);

        List<RoadLeg> roads = new ArrayList<>();
        for (int i = 1; i < order.size(); i++) {
            Optional<RoadLeg> leg = legs.leg(order.get(i - 1), order.get(i));
            if (leg.isEmpty()) {
                return Optional.empty();
            }
            roads.add(leg.get());
        }
        List<Integer> rides = plan.rideSeconds(order, roads);
        for (int i = 0; i < order.size(); i++) {
            if (rides.get(i) - order.get(i).soloSeconds() > settings.maxDelayMinutes() * 60) {
                return Optional.empty();
            }
        }
        double totalKm = plan.totalKm(order, roads);
        double soloKm = order.stream().mapToDouble(MatchRequest::soloKm).sum();
        if (totalKm > soloKm * (1 - settings.minSavingRate())) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(order, totalKm));
    }

}
