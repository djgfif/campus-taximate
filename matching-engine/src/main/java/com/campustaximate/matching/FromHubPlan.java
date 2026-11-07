package com.campustaximate.matching;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 하교. 거점에서 함께 타서 가까운 곳부터 내린다. 마지막 하차자가 결제자다. */
final class FromHubPlan implements TripPlan {

    private static final Comparator<MatchRequest> NEAREST_FIRST = Comparator
            .comparingDouble(MatchRequest::soloKm).thenComparingLong(MatchRequest::id);

    private final MatchingSettings settings;

    FromHubPlan(MatchingSettings settings) {
        this.settings = settings;
    }

    private long leadSeconds() {
        return settings.leadMinutes() * 60L;
    }

    @Override
    public Comparator<MatchRequest> comparator() {
        return NEAREST_FIRST;
    }

    @Override
    public int payerIndex(int size) {
        return size - 1;
    }

    @Override
    public boolean eligible(MatchRequest request, Instant slot) {
        return request.canDepartAt(slot.plusSeconds(leadSeconds()));
    }

    @Override
    public boolean expired(MatchRequest request, Instant slot) {
        return !slot.plusSeconds(leadSeconds()).isBefore(request.windowEnd());
    }

    /** 2명 조합의 기한은 가장 이른 끝 시각의 30분 전이고 그 슬롯을 포함한다. */
    @Override
    public boolean isDeadline(List<MatchRequest> group, Instant slot) {
        Instant earliestEnd = group.stream().map(MatchRequest::windowEnd).min(Instant::compareTo).orElseThrow();
        return !slot.isBefore(earliestEnd.minusSeconds(leadSeconds()));
    }

    @Override
    public Instant urgency(MatchRequest request) {
        return request.windowEnd();
    }

    @Override
    public Optional<Instant> departure(Instant slot) {
        return Optional.of(slot.plusSeconds(leadSeconds()));
    }

    @Override
    public List<Integer> rideSeconds(List<MatchRequest> order, List<RoadLeg> legs) {
        List<Integer> rides = new ArrayList<>();
        int seconds = order.get(0).soloSeconds();
        rides.add(seconds);
        for (RoadLeg leg : legs) {
            seconds += leg.seconds();
            rides.add(seconds);
        }
        return rides;
    }

    @Override
    public double totalKm(List<MatchRequest> order, List<RoadLeg> legs) {
        return order.get(0).soloKm() + legs.stream().mapToDouble(RoadLeg::km).sum();
    }

    @Override
    public TripPath sharedPath(GeoPoint anchor, List<GeoPoint> ordered) {
        return new TripPath(anchor, ordered);
    }

    @Override
    public TripPath soloPath(GeoPoint anchor, GeoPoint own) {
        return new TripPath(anchor, List.of(own));
    }

    @Override
    public boolean reordersOnShrink() {
        return true;
    }
}
