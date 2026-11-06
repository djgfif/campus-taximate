package com.campustaximate.matching;

/** 카카오 확인 단계의 결과. */
public enum Verdict {
    /** 15분, 10% 조건을 통과했다. */
    PASS,
    /** 조건이 깨졌다. 다음 순위 조합을 본다. */
    FAIL,
    /** 통신 실패다. 탈락으로 치지 않고 다음 실행으로 넘긴다. */
    UNAVAILABLE
}
