package com.campustaximate.matching;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

/** 매칭 대상 요청. 출발 가능 시간의 시작과 끝은 포함이다. 단독 값은 실제 도로 값이다. */
public record MatchRequest(long id, long memberId, Hub hub, Gender gender,
                           Instant windowStart, Instant windowEnd, GeoPoint dropoff,
                           double soloKm, int soloSeconds) {

    /** 가까운 곳부터 내리는 순서. */
    public static final Comparator<MatchRequest> DROPOFF_ORDER = Comparator
            .comparingDouble(MatchRequest::soloKm).thenComparingLong(MatchRequest::id);

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
