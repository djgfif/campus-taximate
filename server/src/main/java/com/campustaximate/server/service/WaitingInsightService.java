package com.campustaximate.server.service;

import com.campustaximate.matching.Direction;
import com.campustaximate.matching.GeoPoint;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.domain.TimeBasis;
import com.campustaximate.server.domain.RequestMode;
import com.campustaximate.server.repository.RideRequestRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 대기 중인 요청에 지금 어떤 상황인지 알려 준다. 숫자만 주고 다른 학생의 정보는 주지 않는다. */
@Service
@RequiredArgsConstructor
public class WaitingInsightService {

    /** 안내용 기준이다. 실제 판단은 매칭 엔진이 도로 값으로 한다. */
    private static final double SAME_DIRECTION_DEGREES = 60;

    public enum Stage { NOT_STARTED, ALONE, OTHER_DIRECTION, CANDIDATES }

    public record Insight(Stage stage, int peers, int sameDirection, Instant nextCheckAt, Instant searchStartsAt) {
    }

    private final RideRequestRepository requests;
    private final AppClock clock;

    /** 대기 중이 아니면 null이다. */
    @Transactional(readOnly = true)
    public Insight of(long requestId) {
        RideRequest me = requests.findById(requestId).orElse(null);
        if (me == null || me.getStatus() != RequestStatus.WAITING) {
            return null;
        }
        List<RideRequest> peers = requests.findByStatusAndDirection(RequestStatus.WAITING, me.getDirection()).stream()
                .filter(other -> isPeer(me, other)).toList();
        GeoPoint anchor = anchorOf(me);
        double myBearing = anchor.bearingTo(me.point());
        int sameDirection = (int) peers.stream()
                .filter(other -> gapDegrees(myBearing, anchor.bearingTo(other.point())) <= SAME_DIRECTION_DEGREES)
                .count();
        Instant next = me.getMode() == RequestMode.INSTANT ? AppClock.nextMinuteAfter(clock.now()) : AppClock.nextSlotAfter(clock.now());
        // 예약도 다음 날까지 미리 탐색하며, 확정 자격은 엔진이 따로 판단한다.
        Instant searchStartsAt = me.getRequestedAt() == null ? next : me.getRequestedAt();
        return new Insight(stage(next, searchStartsAt, peers.size(), sameDirection), peers.size(), sameDirection,
                next, searchStartsAt);
    }

    private static Stage stage(Instant next, Instant searchStartsAt, int peers, int sameDirection) {
        if (next.isBefore(searchStartsAt)) {
            return Stage.NOT_STARTED;
        }
        if (peers == 0) {
            return Stage.ALONE;
        }
        return sameDirection == 0 ? Stage.OTHER_DIRECTION : Stage.CANDIDATES;
    }

    private static boolean isPeer(RideRequest me, RideRequest other) {
        if (other.getId().equals(me.getId()) || other.getDirection() != me.getDirection()
                || other.getMember().getGender() != me.getMember().getGender()) {
            return false;
        }
        if (me.getDirection() == Direction.TO_SCHOOL) {
            return other.getSchool().getId().equals(me.getSchool().getId())
                    && Objects.equals(other.getArriveBy(), me.getArriveBy());
        }
        if (other.getTimeBasis() != me.getTimeBasis()) return false;
        java.util.Set<Long> mine = pointIds(me);
        if (pointIds(other).stream().noneMatch(mine::contains)) return false;
        if (me.getTimeBasis() == TimeBasis.ARRIVE) {
            return !other.earliestArrival().isAfter(me.getArriveBy()) && !me.earliestArrival().isAfter(other.getArriveBy());
        }
        return !other.getWindowStart().isAfter(me.getWindowEnd()) && !me.getWindowStart().isAfter(other.getWindowEnd());
    }

    private static java.util.Set<Long> pointIds(RideRequest r) {
        return r.getPoints().isEmpty() ? java.util.Set.of(r.getHub().getId()) : r.getPoints().stream()
                .map(p -> p.getHub().getId()).collect(java.util.stream.Collectors.toSet());
    }

    private static GeoPoint anchorOf(RideRequest r) {
        return r.getHub() != null ? new GeoPoint(r.getHub().getLat(), r.getHub().getLon())
                : new GeoPoint(r.getSchool().getLat(), r.getSchool().getLon());
    }

    private static double gapDegrees(double a, double b) {
        double gap = Math.abs(Math.toDegrees(a - b)) % 360;
        return gap > 180 ? 360 - gap : gap;
    }
}
