package com.campustaximate.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PushServiceTest {

    @Test
    void acceptsOnlyKnownPushServices() {
        assertThat(PushService.allowed("https://fcm.googleapis.com/fcm/send/abc")).isTrue();
        assertThat(PushService.allowed("https://wns2-par02p.notify.windows.com/w/?token=x")).isTrue();
        assertThat(PushService.allowed("https://web.push.apple.com/abc")).isTrue();
        assertThat(PushService.allowed("http://fcm.googleapis.com/fcm/send/abc")).isFalse();
        assertThat(PushService.allowed("https://localhost:8080/api/state")).isFalse();
        assertThat(PushService.allowed("https://fcm.googleapis.com.evil.example/x")).isFalse();
        assertThat(PushService.allowed("not a url")).isFalse();
    }

    @Test
    void fareAccuracyCountsWithinFifteenPercent() {
        var result = FareAccuracyService.of(List.of(
                new Object[] {11500L, 10000L}, new Object[] {11501L, 10000L}, new Object[] {8500L, 10000L}));
        assertThat(result.settled()).isEqualTo(3);
        assertThat(result.within()).isEqualTo(2);
    }
}
