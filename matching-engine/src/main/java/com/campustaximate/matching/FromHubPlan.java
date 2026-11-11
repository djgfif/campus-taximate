package com.campustaximate.matching;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 승차 지점 한 곳에서 모두 타고 가까운 곳부터 내린다. 마지막 하차자가 결제자다(명세 4.2).
 * 출발 기준과 도착 기준 요청을 모두 다룬다. 시간 규칙은 명세 5장.
 */
final class FromHubPlan implements TripPlan {

    /**
     * 가까운 사람부터 내린다. 거리가 같으면 요청 번호가 늦은 사람이 먼저 내려서,
     * 마지막 하차자(결제자)는 같은 거리 중 요청 번호가 빠른 사람이 된다(명세 2장 결제자).
     */
    private static final Comparator<MatchRequest> NEAREST_FIRST = Comparator
            .comparingDouble(MatchRequest::anchorKm)
            .thenComparing(Comparator.comparingLong(MatchRequest::id).reversed());

    private final MatchingSettings settings;

    FromHubPlan(MatchingSettings settings) {
        this.settings = settings;
    }

    private long leadSeconds() {
        return settings.leadMinutes() * 60L;
    }

    private long stepSeconds(MatchRequest request) {
        return (request.instant() ? settings.slotMinutes() : settings.reservationSlotMinutes()) * 60L;
    }

    private boolean reservationRound(Instant slot) {
        return slot.getEpochSecond() % (settings.reservationSlotMinutes() * 60L) == 0;
    }

    /**
     * 사람별 모집 마감(명세 5.3). 출발 기준은 출발 가능 끝 − P, 도착 기준은 서버가 계산해 넣은 deadlineSlot.
     * 즉시 요청은 요청 + 5분(S37, 출발 가능 시간 입력 없음).
     */
    Instant deadline(MatchRequest request) {
        if (request.instant() && request.requestedAt() != null) {
            return request.requestedAt().plusSeconds(settings.instantMatchMinutes() * 60L);
        }
        return request.arrival() ? request.deadlineSlot() : request.windowEnd().minusSeconds(leadSeconds());
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
        if (request.requestedAt() != null && request.requestedAt().isAfter(slot)) {
            return false;
        }
        return !slot.isAfter(deadline(request));
    }

    /** 다음 회차(예약 5분, 즉시 1분)가 마감을 넘기면 이번이 마지막이다. */
    @Override
    public boolean lastChance(MatchRequest request, Instant slot) {
        return slot.plusSeconds(stepSeconds(request)).isAfter(deadline(request));
    }

    /** 예약 요청은 예약 회차(5분)에만 실패 처리한다. 사이 회차에서 즉시 일행에 들어갈 수 있다. */
    @Override
    public boolean expired(MatchRequest request, Instant slot) {
        return lastChance(request, slot) && (request.instant() || reservationRound(slot));
    }

    @Override
    public boolean isDeadline(List<MatchRequest> group, Instant slot) {
        return group.stream().anyMatch(r -> lastChance(r, slot));
    }

    /**
     * 명세 5.1. 즉시 요청이 있으면 찾은 회차에 확정. 예약만이면 예약 회차에서만,
     * 4명이거나 모집 마감 회차이거나 가장 먼저 요청한 사람이 요청한 지 10분이 지났을 때.
     */
    @Override
    public boolean confirmable(List<MatchRequest> group, Instant slot, int maxGroupSize) {
        if (group.stream().anyMatch(MatchRequest::instant)) {
            return true;
        }
        if (!reservationRound(slot)) {
            return false;
        }
        if (group.size() >= maxGroupSize || isDeadline(group, slot)) {
            return true;
        }
        return group.stream().map(MatchRequest::requestedAt)
                .filter(t -> t != null)
                .min(Instant::compareTo)
                .map(t -> !t.plusSeconds(settings.earlyConfirmMinutes() * 60L).isAfter(slot))
                .orElse(false);
    }

    @Override
    public Instant urgency(MatchRequest request) {
        return deadline(request);
    }

    @Override
    public Optional<Instant> departure(Instant slot) {
        return Optional.of(ceilFiveMinutes(slot.plusSeconds(leadSeconds())));
    }

    /**
     * 즉시 요청이 있는 일행(S37, 명세 5.7): 예상 T = 확정 + max(결제 준비, 가장 긴 걷기) + 배차 여유(1분 단위 올림).
     * 즉시 요청은 출발 기준만 받고, 섞인 예약 요청자의 출발 가능 범위 안이어야 한다.
     * 출발 기준: T = 출발 가능 교집합 안에서 확정 + P 이후 가장 이른 5분 단위 시각.
     * 도착 기준: 목표 도착 = 늦어도 시각 중 가장 이른 것. T = 목표 − 마지막 하차자 동승 시간(분 단위 버림).
     * 각자 하차 시각이 자기 도착 범위 안이고 T ≥ 확정 + P여야 한다.
     */
    @Override
    public Schedule schedule(List<MatchRequest> order, List<Integer> rides, Instant slot) {
        if (slot == null) {
            return new Schedule(true, null);
        }
        Instant earliestDeparture = slot.plusSeconds(leadSeconds());
        boolean arrival = order.get(0).arrival();
        if (order.stream().anyMatch(r -> r.arrival() != arrival)) {
            return Schedule.FAIL;
        }
        if (order.stream().anyMatch(MatchRequest::instant)) {
            return arrival ? Schedule.FAIL : instantSchedule(order, slot);
        }
        if (!arrival) {
            Instant from = earliestDeparture;
            Instant to = null;
            for (MatchRequest r : order) {
                if (r.windowStart().isAfter(from)) {
                    from = r.windowStart();
                }
                if (to == null || r.windowEnd().isBefore(to)) {
                    to = r.windowEnd();
                }
            }
            Instant t = ceilFiveMinutes(from);
            return t.isAfter(to) ? Schedule.FAIL : new Schedule(true, t);
        }
        Instant target = order.stream().map(MatchRequest::arriveBy).min(Instant::compareTo).orElseThrow();
        int last = rides.get(rides.size() - 1);
        Instant t = floorMinute(target.minusSeconds(last));
        if (t.isBefore(earliestDeparture)) {
            return Schedule.FAIL;
        }
        for (int i = 0; i < order.size(); i++) {
            Instant drop = t.plusSeconds(rides.get(i));
            MatchRequest r = order.get(i);
            if (drop.isBefore(r.earliestArrival()) || drop.isAfter(r.arriveBy())) {
                return Schedule.FAIL;
            }
        }
        return new Schedule(true, t);
    }

    private Schedule instantSchedule(List<MatchRequest> order, Instant slot) {
        int walk = order.stream().mapToInt(MatchRequest::walkSeconds).max().orElse(0);
        long ready = Math.max(settings.instantPrepMinutes() * 60L, walk);
        Instant t = ceilMinute(slot.plusSeconds(ready + settings.instantDispatchMinutes() * 60L));
        for (MatchRequest r : order) {
            if (r.instant()) {
                continue;
            }
            if (r.windowStart().isAfter(t)) {
                t = ceilMinute(r.windowStart());
            }
        }
        for (MatchRequest r : order) {
            if (!r.instant() && t.isAfter(r.windowEnd())) {
                return Schedule.FAIL;
            }
        }
        return new Schedule(true, t);
    }

    private static Instant ceilMinute(Instant time) {
        long s = time.getEpochSecond();
        long r = Math.floorMod(s, 60);
        return Instant.ofEpochSecond(r == 0 && time.getNano() == 0 ? s : s - r + 60);
    }

    static Instant ceilFiveMinutes(Instant time) {
        long s = time.getEpochSecond();
        long r = Math.floorMod(s, 300);
        return Instant.ofEpochSecond(r == 0 && time.getNano() == 0 ? s : s - r + 300);
    }

    private static Instant floorMinute(Instant time) {
        long s = time.getEpochSecond();
        return Instant.ofEpochSecond(s - Math.floorMod(s, 60));
    }

    /** 승차 지점→첫 하차는 첫 하차자의 승차 지점 기준 단독 시간, 그 뒤는 구간을 누적한다. */
    @Override
    public List<Integer> rideSeconds(List<MatchRequest> order, List<RoadLeg> legs) {
        List<Integer> rides = new ArrayList<>();
        int seconds = order.get(0).anchorSeconds();
        rides.add(seconds);
        for (RoadLeg leg : legs) {
            seconds += leg.seconds();
            rides.add(seconds);
        }
        return rides;
    }

    @Override
    public double totalKm(List<MatchRequest> order, List<RoadLeg> legs) {
        return order.get(0).anchorKm() + legs.stream().mapToDouble(RoadLeg::km).sum();
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
