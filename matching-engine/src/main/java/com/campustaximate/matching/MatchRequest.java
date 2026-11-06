package com.campustaximate.matching;

import java.time.Instant;
import java.util.Objects;

/** 묶기 대상 요청. 출발 가능 시간의 시작과 끝은 포함이다. */
public record MatchRequest(long id, long memberId, Hub hub, Gender gender,
                           Instant windowStart, Instant windowEnd, GeoPoint dropoff) {

    public MatchRequest {
        Objects.requireNonNull(hub);
        Objects.requireNonNull(gender);
        Objects.requireNonNull(windowStart);
        Objects.requireNonNull(windowEnd);
        Objects.requireNonNull(dropoff);
        if (windowEnd.isBefore(windowStart)) {
            throw new IllegalArgumentException("windowEnd is before windowStart: " + id);
        }
    }

    public boolean canDepartAt(Instant departure) {
        return !departure.isBefore(windowStart) && !departure.isAfter(windowEnd);
    }
}
