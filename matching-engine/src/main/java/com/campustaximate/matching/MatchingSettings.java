package com.campustaximate.matching;

/** 엔진 설정값. 기본값은 구현 기준 문서를 따른다. */
public record MatchingSettings(int maxDelayMinutes, double minSavingRate, int maxCandidates, int maxGroupSize,
                               int maxVerifications, int leadMinutes, int slotMinutes) {

    public static MatchingSettings defaults() {
        return new MatchingSettings(15, 0.10, 12, 4, 3, 30, 5);
    }
}
