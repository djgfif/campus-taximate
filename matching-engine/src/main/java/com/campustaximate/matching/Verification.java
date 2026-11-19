package com.campustaximate.matching;

import java.time.Instant;

/**
 * 2차 검사 결과. departure는 실제 경로로 다시 정한 출발 시각 T다(명세 5.2).
 * 도착 기준 일행은 실제 동승 시간이 1차 추정과 다르면 T가 바뀐다. null이면 1차 추정 T를 쓴다.
 */
public record Verification(Verdict verdict, Instant departure) {

    public static Verification of(Verdict verdict) {
        return new Verification(verdict, null);
    }
}
