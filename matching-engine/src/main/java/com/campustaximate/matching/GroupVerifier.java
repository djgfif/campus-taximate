package com.campustaximate.matching;

/** 뽑힌 조합을 실제 경로 값으로 확인하는 포트. 카카오 구현은 엔진 밖에 둔다. */
@FunctionalInterface
public interface GroupVerifier {
    Verdict verify(Candidate candidate);
}
