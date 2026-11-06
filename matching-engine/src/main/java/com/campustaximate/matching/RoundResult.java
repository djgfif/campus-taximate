package com.campustaximate.matching;

import java.util.List;

/** 슬롯 한 번의 결과. expired는 이번 슬롯이 마지막이었는데 못 묶인 요청이다. */
public record RoundResult(List<ConfirmedGroup> confirmed, List<MatchRequest> expired) {

    public RoundResult {
        confirmed = List.copyOf(confirmed);
        expired = List.copyOf(expired);
    }
}
