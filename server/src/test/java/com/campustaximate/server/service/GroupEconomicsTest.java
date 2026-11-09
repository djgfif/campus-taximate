package com.campustaximate.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.campustaximate.server.route.RouteClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class GroupEconomicsTest {

    private static RouteClient.Route solo(long fare, int seconds) {
        return new RouteClient.Route(4.0, seconds, fare);
    }

    @Test
    void exactlyTenPercentSavingsAndFifteenMinutesDelayPass() {
        assertThat(GroupEconomics.passes(9_000,
                List.of(solo(4_000, 600), solo(6_000, 900)),
                List.of(1_500, 900))).isTrue();
    }

    @Test
    void lessThanTenPercentTotalSavingsFails() {
        assertThat(GroupEconomics.passes(9_001,
                List.of(solo(4_000, 600), solo(6_000, 900)),
                List.of(600, 900))).isFalse();
    }

    @Test
    void moreThanFifteenMinutesAdditionalRideFails() {
        assertThat(GroupEconomics.passes(8_000,
                List.of(solo(4_000, 600), solo(6_000, 900)),
                List.of(1_501, 900))).isFalse();
    }

    @Test
    void rejectsInvalidOrIncompleteRouteData() {
        assertThat(GroupEconomics.passes(0,
                List.of(solo(4_000, 600), solo(6_000, 900)), List.of(600, 900))).isFalse();
        assertThat(GroupEconomics.passes(8_000,
                List.of(solo(4_000, 600), solo(6_000, 900)), List.of(600))).isFalse();
        assertThat(GroupEconomics.passes(8_000,
                List.of(solo(0, 600), solo(6_000, 900)), List.of(600, 900))).isFalse();
    }
}
