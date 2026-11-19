package com.campustaximate.matching;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 매칭 대상 요청. anchor는 승차 지점(하교의 거점 포함)이고, own은 각자의 도착지다.
 * 한 사용자가 승차 지점을 여러 곳 고르면 지점마다 하나씩 만든다(id는 같고 anchorId가 다르다).
 *
 * <p>시간은 둘 중 하나다. 출발 기준은 windowStart~windowEnd, 도착 기준은 earliestArrival~arriveBy와
 * deadlineSlot(모집 마감)을 쓴다. 등교 순차 승차(TO_SCHOOL)는 추가기능으로 남겨 둔 옛 형태다.
 *
 * <p>soloKm·soloSeconds는 출발지(집)에서 도착지까지 혼자 갈 때 값이고, anchorKm·anchorSeconds는
 * 승차 지점에서 도착지까지 값이다. walkSeconds는 출발지에서 승차 지점까지 걷는 시간이다.
 * 지연 = 걷는 시간 + 동승 시간 - 단독 시간(명세 4.3).
 */
public record MatchRequest(long id, long memberId, Direction direction, long anchorId, GeoPoint anchor, Gender gender,
                           Instant windowStart, Instant windowEnd, Instant arriveBy, Instant deadlineSlot,
                           GeoPoint own, double soloKm, int soloSeconds, Set<Long> avoidMemberIds,
                           int walkSeconds, double anchorKm, int anchorSeconds, Instant requestedAt,
                           boolean instant, Instant earliestArrival) {

    /** 같은 풀 안에서만 매칭한다. 승차 지점, 성별, 시간 기준(출발·도착)이 같아야 한다. */
    public record PoolKey(Direction direction, long anchorId, Gender gender, String timeKey) {
    }

    public MatchRequest {
        avoidMemberIds = Set.copyOf(avoidMemberIds);
        Objects.requireNonNull(direction);
        Objects.requireNonNull(anchor);
        Objects.requireNonNull(gender);
        Objects.requireNonNull(own);
        if (walkSeconds < 0 || anchorSeconds < 0) {
            throw new IllegalArgumentException("negative seconds: " + id);
        }
        if (direction == Direction.FROM_HUB && windowStart != null) {
            Objects.requireNonNull(windowEnd);
            if (windowEnd.isBefore(windowStart)) {
                throw new IllegalArgumentException("windowEnd is before windowStart: " + id);
            }
        } else {
            Objects.requireNonNull(arriveBy);
            Objects.requireNonNull(deadlineSlot);
            if (earliestArrival == null) {
                earliestArrival = arriveBy;
            }
            if (earliestArrival.isAfter(arriveBy)) {
                throw new IllegalArgumentException("earliestArrival is after arriveBy: " + id);
            }
        }
    }

    /** 예전 형태(승차 지점=거점, 걷기 없음, 즉시 아님). */
    public MatchRequest(long id, long memberId, Direction direction, long anchorId, GeoPoint anchor, Gender gender,
                        Instant windowStart, Instant windowEnd, Instant arriveBy, Instant deadlineSlot,
                        GeoPoint own, double soloKm, int soloSeconds, Set<Long> avoidMemberIds) {
        this(id, memberId, direction, anchorId, anchor, gender, windowStart, windowEnd, arriveBy, deadlineSlot,
                own, soloKm, soloSeconds, avoidMemberIds, 0, soloKm, soloSeconds, null, false, null);
    }

    /** 기존 호출은 회피 없는 요청을 만든다. */
    public MatchRequest(long id, long memberId, Direction direction, long anchorId, GeoPoint anchor, Gender gender,
                        Instant windowStart, Instant windowEnd, Instant arriveBy, Instant deadlineSlot,
                        GeoPoint own, double soloKm, int soloSeconds) {
        this(id, memberId, direction, anchorId, anchor, gender, windowStart, windowEnd, arriveBy, deadlineSlot,
                own, soloKm, soloSeconds, Set.of());
    }

    /** 하교 요청(출발 기준, 승차 지점=거점). */
    public MatchRequest(long id, long memberId, Hub hub, Gender gender, Instant windowStart, Instant windowEnd,
                        GeoPoint dropoff, double soloKm, int soloSeconds) {
        this(id, memberId, Direction.FROM_HUB, hub.id(), hub.location(), gender, windowStart, windowEnd, null, null,
                dropoff, soloKm, soloSeconds);
    }

    /** 승차 지점에 모여 타는 도착 기준 요청(예: 등교). deadlineSlot은 모집 마감. */
    public static MatchRequest arriving(long id, long memberId, Hub point, Gender gender,
                                        Instant earliestArrival, Instant arriveBy, Instant deadlineSlot,
                                        GeoPoint destination, double soloKm, int soloSeconds) {
        return new MatchRequest(id, memberId, Direction.FROM_HUB, point.id(), point.location(), gender, null, null,
                arriveBy, deadlineSlot, destination, soloKm, soloSeconds, Set.of(), 0, soloKm, soloSeconds, null,
                false, earliestArrival);
    }

    /** 등교 순차 승차(추가기능으로 남긴 옛 형태). */
    public static MatchRequest toSchool(long id, long memberId, long schoolId, GeoPoint school, Gender gender,
                                        Instant arriveBy, Instant deadlineSlot, GeoPoint boarding,
                                        double soloKm, int soloSeconds) {
        return new MatchRequest(id, memberId, Direction.TO_SCHOOL, schoolId, school, gender, null, null, arriveBy,
                deadlineSlot, boarding, soloKm, soloSeconds);
    }

    public MatchRequest withAvoidMemberIds(Set<Long> ids) {
        return new MatchRequest(id, memberId, direction, anchorId, anchor, gender, windowStart, windowEnd,
                arriveBy, deadlineSlot, own, soloKm, soloSeconds, ids, walkSeconds, anchorKm, anchorSeconds,
                requestedAt, instant, earliestArrival);
    }

    /** 승차 지점까지 걷는 시간과 승차 지점→도착지 값을 넣는다. */
    public MatchRequest atPoint(int walkSeconds, double anchorKm, int anchorSeconds) {
        return new MatchRequest(id, memberId, direction, anchorId, anchor, gender, windowStart, windowEnd,
                arriveBy, deadlineSlot, own, soloKm, soloSeconds, avoidMemberIds, walkSeconds, anchorKm,
                anchorSeconds, requestedAt, instant, earliestArrival);
    }

    /** 요청 시각과 즉시 여부를 넣는다. */
    public MatchRequest requested(Instant requestedAt, boolean instant) {
        return new MatchRequest(id, memberId, direction, anchorId, anchor, gender, windowStart, windowEnd,
                arriveBy, deadlineSlot, own, soloKm, soloSeconds, avoidMemberIds, walkSeconds, anchorKm,
                anchorSeconds, requestedAt, instant, earliestArrival);
    }

    public boolean avoids(MatchRequest other) {
        return avoidMemberIds.contains(other.memberId) || other.avoidMemberIds.contains(memberId);
    }

    /** 도착 기준 요청인가. */
    public boolean arrival() {
        return direction == Direction.FROM_HUB && windowStart == null;
    }

    public PoolKey poolKey() {
        String timeKey = direction == Direction.TO_SCHOOL ? "TS:" + arriveBy : (arrival() ? "ARRIVE" : "DEPART");
        return new PoolKey(direction, anchorId, gender, timeKey);
    }

    public boolean canDepartAt(Instant departure) {
        return !departure.isBefore(windowStart) && !departure.isAfter(windowEnd);
    }
}
