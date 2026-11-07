package com.campustaximate.matching;

import java.time.Instant;
import java.util.List;

/** 확정된 일행. departure는 슬롯으로 정해지는 방향만 값이 있다. 등교는 경로 확인 단계가 구한다. */
public record ConfirmedGroup(List<MatchRequest> order, MatchRequest payer, Instant departure) {

    public ConfirmedGroup {
        order = List.copyOf(order);
    }
}
