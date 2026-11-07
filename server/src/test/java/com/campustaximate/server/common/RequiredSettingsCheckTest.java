package com.campustaximate.server.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequiredSettingsCheckTest {

    @Test
    void productionNeedsEveryKey() {
        assertThat(RequiredSettingsCheck.missing(false, "", " ", null))
                .containsExactly("ACCOUNT_KEY", "KAKAO_REST_KEY", "MAIL_HOST");
        assertThat(RequiredSettingsCheck.missing(false, "a", "b", "c")).isEmpty();
    }

    @Test
    void demoRunsWithoutThem() {
        assertThat(RequiredSettingsCheck.missing(true, "", "", "")).isEmpty();
    }
}
