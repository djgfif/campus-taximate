package com.campustaximate.matching;

import java.util.Optional;

/** 하차 지점 사이의 도로 값을 주는 포트. 값이 없으면 그 구간을 지나는 조합을 고르지 않는다. */
@FunctionalInterface
public interface LegProvider {
    Optional<RoadLeg> leg(MatchRequest from, MatchRequest to);
}
