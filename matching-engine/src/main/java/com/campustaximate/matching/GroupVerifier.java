package com.campustaximate.matching;

/**
 * 뽑힌 조합을 실제 경로 값으로 확인하는 포트. 카카오 구현은 엔진 밖에 둔다.
 * 출발 시각까지 다시 정하는 구현은 {@link #check(Candidate)}를 덮어쓴다.
 */
@FunctionalInterface
public interface GroupVerifier {

    Verdict verify(Candidate candidate);

    /** 판정과 실제 경로로 정한 출발 시각. 기본은 판정만 돌려주고 1차 추정 T를 쓴다. */
    default Verification check(Candidate candidate) {
        return Verification.of(verify(candidate));
    }

    /** 최근에 탈락한 조합이면 확인 횟수를 쓰지 않고 건너뛴다. */
    default boolean recentlyRejected(Candidate candidate) {
        return false;
    }
}
