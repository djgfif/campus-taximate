package com.campustaximate.matching;

import java.util.List;

/** 추정 단계를 통과한 조합. order는 승하차 순서다. */
public record Candidate(List<MatchRequest> order, double totalKm) {

    public Candidate {
        order = List.copyOf(order);
    }

    public int size() {
        return order.size();
    }

    public List<Long> sortedIds() {
        return order.stream().map(MatchRequest::id).sorted().toList();
    }
}
