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

    /** 가까운 곳부터 내리는 순서로 시험해 조건을 통과하면 조합을 돌려준다. */
    public Optional<Candidate> evaluate(List<MatchRequest> group, LegProvider legs) {
        List<MatchRequest> order = new ArrayList<>(group);
        order.sort(MatchRequest.DROPOFF_ORDER);

        MatchRequest first = order.get(0);
        double cumulativeKm = first.soloKm();
        int cumulativeSeconds = first.soloSeconds();
        for (int i = 1; i < order.size(); i++) {
            MatchRequest rider = order.get(i);
            Optional<RoadLeg> leg = legs.leg(order.get(i - 1), rider);
            if (leg.isEmpty()) {
                return Optional.empty();
            }
            cumulativeKm += leg.get().km();
            cumulativeSeconds += leg.get().seconds();
            int delaySeconds = cumulativeSeconds - rider.soloSeconds();
            if (delaySeconds > settings.maxDelayMinutes() * 60) {
                return Optional.empty();
            }
        }
        double soloKm = order.stream().mapToDouble(MatchRequest::soloKm).sum();
        if (cumulativeKm > soloKm * (1 - settings.minSavingRate())) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(order, cumulativeKm));
    }

}
