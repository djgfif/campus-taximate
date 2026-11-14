package com.campustaximate.matching;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 1차 검사(명세 4.3). 구간 값으로 지연 15분과 시간 조건을 본다.
 * 지연 = 걷는 시간 + 동승 시간 − 단독 시간. 거리 절약은 걸러내지 않고 순위에만 쓴다.
 * 요금 10% 조건은 2차(카카오) 검사가 본다.
 */
public final class EstimateCalculator {

    private final MatchingSettings settings;

    public EstimateCalculator(MatchingSettings settings) {
        this.settings = settings;
    }

    /** 시간 조건 없이 지연만 본다. */
    public Optional<Candidate> evaluate(List<MatchRequest> group, LegProvider legs) {
        return evaluate(group, legs, null);
    }

    /** slot이 있으면 출발 시각을 정하고 출발·도착 범위를 본다. */
    public Optional<Candidate> evaluate(List<MatchRequest> group, LegProvider legs, Instant slot) {
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
            MatchRequest r = order.get(i);
            if (r.walkSeconds() + rides.get(i) - r.soloSeconds() > settings.maxDelayMinutes() * 60) {
                return Optional.empty();
            }
        }
        TripPlan.Schedule schedule = plan.schedule(order, rides, slot);
        if (!schedule.ok()) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(order, plan.totalKm(order, roads), schedule.departure(), rides));
    }
}
