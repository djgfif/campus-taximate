package com.campustaximate.matching;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 등교. 학교에서 먼 사람부터 차례로 타고 학교에서 함께 내린다. 첫 승차자가 결제자다. */
public final class ToSchoolPlan implements TripPlan {

    /** 승차 지점 한 곳에 머무는 시간. */
    public static final int STOP_SECONDS = 120;

    private static final Comparator<MatchRequest> FARTHEST_FIRST = Comparator
            .comparingDouble(MatchRequest::soloKm).reversed().thenComparingLong(MatchRequest::id);

    @Override
    public Comparator<MatchRequest> comparator() {
        return FARTHEST_FIRST;
    }

    @Override
    public int payerIndex(int size) {
        return 0;
    }

    @Override
    public boolean eligible(MatchRequest request, Instant slot) {
        return !slot.isAfter(request.deadlineSlot());
    }

    @Override
    public boolean expired(MatchRequest request, Instant slot) {
        return !slot.isBefore(request.deadlineSlot());
    }

    @Override
    public boolean isDeadline(List<MatchRequest> group, Instant slot) {
        Instant earliest = group.stream().map(MatchRequest::deadlineSlot).min(Instant::compareTo).orElseThrow();
        return !slot.isBefore(earliest);
    }

    @Override
    public Instant urgency(MatchRequest request) {
        return request.deadlineSlot();
    }

    @Override
    public Optional<Instant> departure(Instant slot) {
        return Optional.empty();
    }

    /** 내 지점부터 학교까지의 주행 시간에 뒤에 남은 정차마다 2분을 더한다. */
    @Override
    public List<Integer> rideSeconds(List<MatchRequest> order, List<RoadLeg> legs) {
        int size = order.size();
        List<Integer> rides = new ArrayList<>(Collections.nCopies(size, 0));
        int seconds = order.get(size - 1).soloSeconds();
        rides.set(size - 1, seconds);
        for (int i = size - 2; i >= 0; i--) {
            RoadLeg leg = legs.get(i);
            seconds += leg.seconds() + (leg.km() > 0 ? STOP_SECONDS : 0);
            rides.set(i, seconds);
        }
        return rides;
    }

    @Override
    public double totalKm(List<MatchRequest> order, List<RoadLeg> legs) {
        return order.get(order.size() - 1).soloKm() + legs.stream().mapToDouble(RoadLeg::km).sum();
    }

    @Override
    public TripPath sharedPath(GeoPoint anchor, List<GeoPoint> ordered) {
        List<GeoPoint> stops = new ArrayList<>(ordered.subList(1, ordered.size()));
        stops.add(anchor);
        return new TripPath(ordered.get(0), stops);
    }

    @Override
    public TripPath soloPath(GeoPoint anchor, GeoPoint own) {
        return new TripPath(own, List.of(anchor));
    }

    @Override
    public boolean reordersOnShrink() {
        return false;
    }
}
