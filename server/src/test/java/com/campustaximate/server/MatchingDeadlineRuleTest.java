package com.campustaximate.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.campustaximate.server.common.InstantMatchingConfiguration;
import org.junit.jupiter.api.Test;

/** S42: 운영 기본값은 조기 확정 없이 마감 회차까지 모은다. 규칙 자체는 엔진의 SpecAcceptanceTest(s42…)가 본다. */
class MatchingDeadlineRuleTest {
    @Test
    void productionDefaultWaitsUntilDeadline() {
        assertThat(new InstantMatchingConfiguration().matchingSettings().earlyConfirmMinutes()).isZero();
    }
}
