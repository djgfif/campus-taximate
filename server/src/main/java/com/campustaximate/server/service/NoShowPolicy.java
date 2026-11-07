package com.campustaximate.server.service;

import java.time.Duration;

/** 노쇼 반복 제한. 돈은 받지 않고, 최근 30일 노쇼 횟수에 따라 새 요청을 막는다. 값은 설정값이다. */
public final class NoShowPolicy {
    public static final Duration WINDOW = Duration.ofDays(30);
    public static final int FIRST_THRESHOLD = 2;
    public static final Duration FIRST_RESTRICTION = Duration.ofDays(7);
    public static final int SECOND_THRESHOLD = 3;
    public static final Duration SECOND_RESTRICTION = Duration.ofDays(30);

    private NoShowPolicy() { }

    /** 최근 30일 노쇼 횟수에 맞는 제한 기간. 제한이 없으면 null. */
    public static Duration restrictionFor(long recentNoShows) {
        if (recentNoShows >= SECOND_THRESHOLD) {
            return SECOND_RESTRICTION;
        }
        if (recentNoShows >= FIRST_THRESHOLD) {
            return FIRST_RESTRICTION;
        }
        return null;
    }
}
