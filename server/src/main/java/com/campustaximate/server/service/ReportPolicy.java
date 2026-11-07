package com.campustaximate.server.service;

import java.time.Duration;

public final class ReportPolicy {
    public static final int PARTY_THRESHOLD = 3;
    public static final Duration RESTRICTION_DURATION = Duration.ofDays(7);
    private ReportPolicy() { }
}
