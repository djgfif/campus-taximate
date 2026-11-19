package com.campustaximate.server.route;

import static org.assertj.core.api.Assertions.assertThat;
import com.campustaximate.matching.GeoPoint;
import org.junit.jupiter.api.Test;

class ConservativeWalkEstimatorTest {
    @Test
    void roundsUpWalkingMinutesAndAddsOneMinute() {
        GeoPoint origin = new GeoPoint(37, 127);
        GeoPoint point = new GeoPoint(37.001, 127);
        var walk = new ConservativeWalkEstimator().estimate(origin, point);
        assertThat(walk.seconds()).isEqualTo(240);
        assertThat(walk.estimated()).isTrue();
    }
}
