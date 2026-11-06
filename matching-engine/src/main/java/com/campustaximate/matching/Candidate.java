package com.campustaximate.matching;

import java.util.List;

/** 추정 단계를 통과한 조합. dropoffOrder는 하차 순서다. */
public record Candidate(List<MatchRequest> dropoffOrder, double totalKm) {

    public Candidate {
        dropoffOrder = List.copyOf(dropoffOrder);
    }

    public int size() {
        return dropoffOrder.size();
    }

    public List<Long> sortedIds() {
        return dropoffOrder.stream().map(MatchRequest::id).sorted().toList();
    }
}
