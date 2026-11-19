package com.campustaximate.matching;

import java.time.Instant;
import java.util.List;

/**
 * 1차 검사(구간 값)를 통과한 조합. order는 하차 순서다.
 * departure는 1차 계산의 출발 시각 T(등교 순차 승차는 없음), rides는 각자 승차→하차 시간(초)이다.
 */
public record Candidate(List<MatchRequest> order, double totalKm, Instant departure, List<Integer> rides) {

    public Candidate {
        order = List.copyOf(order);
        rides = rides == null ? List.of() : List.copyOf(rides);
    }

    public Candidate(List<MatchRequest> order, double totalKm) {
        this(order, totalKm, null, List.of());
    }

    public int size() {
        return order.size();
    }

    public long anchorId() {
        return order.get(0).anchorId();
    }

    public List<Long> sortedIds() {
        return order.stream().map(MatchRequest::id).sorted().toList();
    }

    /** 걷는 시간 합(초). 공통 승차 지점이 여럿일 때 먼저 본다(명세 4.2). */
    public int walkSum() {
        return order.stream().mapToInt(MatchRequest::walkSeconds).sum();
    }

    /** 승차 지점에서 각자 혼자 갈 거리 합에서 일행 거리를 뺀 값. 순위에만 쓴다. */
    public double savedKm() {
        return order.stream().mapToDouble(MatchRequest::anchorKm).sum() - totalKm;
    }

    /** 가장 큰 지연(초). rides가 없으면 0. */
    public int maxDelay() {
        int max = 0;
        for (int i = 0; i < rides.size(); i++) {
            MatchRequest r = order.get(i);
            max = Math.max(max, r.walkSeconds() + rides.get(i) - r.soloSeconds());
        }
        return max;
    }
}
