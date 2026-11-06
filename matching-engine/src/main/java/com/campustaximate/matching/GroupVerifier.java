package com.campustaximate.matching;

/** 뽑힌 조합을 실제 경로 값으로 확인하는 포트. 카카오 구현은 엔진 밖에 둔다. */
@FunctionalInterface
public interface GroupVerifier {

    Verdict verify(Candidate candidate);

    /** 최근에 탈락한 조합이면 확인 횟수를 쓰지 않고 건너뛴다. */
    default boolean recentlyRejected(Candidate candidate) {
        return false;
    }
}
