package com.campustaximate.server.common;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
class InstantMatchingConfigurationTest {
    @Test void serverAndEngineUseTheSameInstantSettings() {
        var config=new InstantMatchingConfiguration();
        assertThat(config.matchingSettings().instantMatchMinutes()).isEqualTo(5);
        assertThat(config.matchingSettings().instantPrepMinutes()).isEqualTo(5);
        assertThat(config.matchingSettings().instantDispatchMinutes()).isEqualTo(5);
        assertThat(config.getInstantMaxWalkSeconds()).isEqualTo(300);
        config.setInstantMatchMinutes(4);config.setInstantPrepMinutes(2);config.setInstantDispatchMinutes(6);
        assertThat(config.matchingSettings().instantMatchMinutes()).isEqualTo(config.getInstantMatchMinutes());
        assertThat(config.matchingSettings().instantPrepMinutes()).isEqualTo(config.getInstantPrepMinutes());
        assertThat(config.matchingSettings().instantDispatchMinutes()).isEqualTo(config.getInstantDispatchMinutes());
    }
}
