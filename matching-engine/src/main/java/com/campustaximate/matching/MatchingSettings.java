package com.campustaximate.matching;

/**
 * 엔진 설정값(명세 4~5장). leadMinutes는 준비 시간 P(확정과 출발 사이 최소 간격)다.
 * slotMinutes는 엔진이 도는 가장 짧은 주기(즉시 매칭 1분)이고, reservationSlotMinutes는
 * 즉시 요청이 없는 예약 일행을 볼 주기(5분)다. earlyConfirmMinutes는 2~3명 예약 일행을
 * 모집 마감 전에 확정할 수 있게 되는 시간(가장 먼저 요청한 사람 기준)이다.
 * instant* 값은 즉시 매칭 근거리 준비형(명세 5.7, S37): 모집 시간, 결제 준비 최소 시간, 배차 여유.
 */
public record MatchingSettings(int maxDelayMinutes, double minSavingRate, int maxCandidates, int maxGroupSize,
                               int maxVerifications, int leadMinutes, int slotMinutes,
                               int reservationSlotMinutes, int earlyConfirmMinutes, long searchMillis,
                               int instantMatchMinutes, int instantPrepMinutes, int instantDispatchMinutes) {

    public MatchingSettings(int maxDelayMinutes, double minSavingRate, int maxCandidates, int maxGroupSize,
                            int maxVerifications, int leadMinutes, int slotMinutes,
                            int reservationSlotMinutes, int earlyConfirmMinutes, long searchMillis) {
        this(maxDelayMinutes, minSavingRate, maxCandidates, maxGroupSize, maxVerifications, leadMinutes, slotMinutes,
                reservationSlotMinutes, earlyConfirmMinutes, searchMillis, 5, 3, 5);
    }

    public MatchingSettings(int maxDelayMinutes, double minSavingRate, int maxCandidates, int maxGroupSize,
                            int maxVerifications, int leadMinutes, int slotMinutes) {
        this(maxDelayMinutes, minSavingRate, maxCandidates, maxGroupSize, maxVerifications, leadMinutes, slotMinutes,
                slotMinutes, 10, 1000);
    }

    public static MatchingSettings defaults() {
        return new MatchingSettings(15, 0.10, 12, 4, 3, 25, 5, 5, 10, 1000);
    }

    /** 즉시 매칭용: 1분마다 돌고 예약 일행은 5분 회차에만 본다. */
    public static MatchingSettings everyMinute() {
        return new MatchingSettings(15, 0.10, 12, 4, 3, 25, 1, 5, 10, 1000);
    }
}
