package com.campustaximate.matching;

import java.time.Instant;
import java.util.List;

/** 확정된 일행. 결제자는 마지막 하차자다. */
public record ConfirmedGroup(List<MatchRequest> dropoffOrder, Instant departure) {

    public ConfirmedGroup {
        dropoffOrder = List.copyOf(dropoffOrder);
    }

    public MatchRequest payer() {
        return dropoffOrder.get(dropoffOrder.size() - 1);
    }
}
