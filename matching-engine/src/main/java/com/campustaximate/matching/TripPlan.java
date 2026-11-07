package com.campustaximate.matching;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 방향마다 다른 규칙을 한곳에 모은다. 나머지 매칭 코드는 방향을 몰라도 된다. */
public interface TripPlan {

    /** 승하차 순서를 정하는 기준. */
    Comparator<MatchRequest> comparator();

    /** order 안에서 결제자의 위치. */
    int payerIndex(int size);

    /** 이 슬롯의 풀에 들어가는가. */
    boolean eligible(MatchRequest request, Instant slot);

    /** 이 슬롯 뒤로 기회가 없는가. */
    boolean expired(MatchRequest request, Instant slot);

    /** 2명 조합을 확정해도 되는 기한인가. */
    boolean isDeadline(List<MatchRequest> group, Instant slot);

    /** 먼저 처리할 요청을 고르는 기준 시각. */
    Instant urgency(MatchRequest request);

    /** 슬롯으로 정해지는 출발 시각. 경로 조회가 필요한 방향은 비어 있다. */
    Optional<Instant> departure(Instant slot);

    /** order를 따라 탈 때 각자 차 안에 있는 시간(초). legs는 order의 앞사람에서 뒷사람 구간이다. */
    List<Integer> rideSeconds(List<MatchRequest> order, List<RoadLeg> legs);

    /** order를 따라 갈 때의 전체 거리. */
    double totalKm(List<MatchRequest> order, List<RoadLeg> legs);

    /** 일행 전체의 경로. ordered는 승하차 순서의 개인 지점이다. */
    TripPath sharedPath(GeoPoint anchor, List<GeoPoint> ordered);

    /** 혼자 갈 때의 경로. */
    TripPath soloPath(GeoPoint anchor, GeoPoint own);

    /** 일행이 줄어들 때 새 단독 값으로 순서를 다시 정하는가. 승차 시각이 고정된 방향은 순서를 유지한다. */
    boolean reordersOnShrink();

    default List<MatchRequest> order(List<MatchRequest> group) {
        List<MatchRequest> order = new ArrayList<>(group);
        order.sort(comparator());
        return order;
    }

    default MatchRequest payer(List<MatchRequest> order) {
        return order.get(payerIndex(order.size()));
    }

    static TripPlan of(Direction direction, MatchingSettings settings) {
        return switch (direction) {
            case FROM_HUB -> new FromHubPlan(settings);
            case TO_SCHOOL -> new ToSchoolPlan();
        };
    }

    static TripPlan of(Direction direction) {
        return of(direction, MatchingSettings.defaults());
    }
}
