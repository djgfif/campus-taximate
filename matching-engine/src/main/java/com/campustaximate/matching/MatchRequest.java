package com.campustaximate.matching;

import java.time.Instant;
import java.util.Objects;

/**
 * 매칭 대상 요청. anchor는 하교의 거점이나 등교의 학교이고, own은 각자의 하차 또는 승차 지점이다.
 * 하교는 windowStart, windowEnd를, 등교는 arriveBy, deadlineSlot을 쓴다. 단독 값은 실제 도로 값이다.
 */
public record MatchRequest(long id, long memberId, Direction direction, long anchorId, GeoPoint anchor, Gender gender,
                           Instant windowStart, Instant windowEnd, Instant arriveBy, Instant deadlineSlot,
                           GeoPoint own, double soloKm, int soloSeconds) {

    /** 같은 풀 안에서만 매칭한다. 등교는 도착 희망 시각까지 같아야 한다. */
    public record PoolKey(Direction direction, long anchorId, Gender gender, Instant arriveBy) {
    }

    public MatchRequest {
        Objects.requireNonNull(direction);
        Objects.requireNonNull(anchor);
        Objects.requireNonNull(gender);
        Objects.requireNonNull(own);
        if (direction == Direction.FROM_HUB) {
            Objects.requireNonNull(windowStart);
            Objects.requireNonNull(windowEnd);
            if (windowEnd.isBefore(windowStart)) {
                throw new IllegalArgumentException("windowEnd is before windowStart: " + id);
            }
        } else {
            Objects.requireNonNull(arriveBy);
            Objects.requireNonNull(deadlineSlot);
        }
    }

    /** 하교 요청. */
    public MatchRequest(long id, long memberId, Hub hub, Gender gender, Instant windowStart, Instant windowEnd,
                        GeoPoint dropoff, double soloKm, int soloSeconds) {
        this(id, memberId, Direction.FROM_HUB, hub.id(), hub.location(), gender, windowStart, windowEnd, null, null,
                dropoff, soloKm, soloSeconds);
    }

    /** 등교 요청. */
    public static MatchRequest toSchool(long id, long memberId, long schoolId, GeoPoint school, Gender gender,
                                        Instant arriveBy, Instant deadlineSlot, GeoPoint boarding,
                                        double soloKm, int soloSeconds) {
        return new MatchRequest(id, memberId, Direction.TO_SCHOOL, schoolId, school, gender, null, null, arriveBy,
                deadlineSlot, boarding, soloKm, soloSeconds);
    }

    public PoolKey poolKey() {
        return new PoolKey(direction, anchorId, gender, arriveBy);
    }

    public boolean canDepartAt(Instant departure) {
        return !departure.isBefore(windowStart) && !departure.isAfter(windowEnd);
    }
}
