package com.campustaximate.server.common;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** 모든 업무 시각의 기준. 시연 모드에서는 시계를 앞으로 옮길 수 있다. */
@Component
public class AppClock {

    public static final int SLOT_MINUTES = 5;

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    public Instant now() {
        return Instant.now().plus(offset.get());
    }

    public void moveTo(Instant target) {
        offset.set(Duration.between(Instant.now(), target));
    }

    /** 매칭 회차는 1분, 요청 입력은 기존처럼 5분 단위다. */
    public static Instant minuteOf(Instant time) {
        return time.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    }

    public static Instant nextMinuteAfter(Instant time) {
        return minuteOf(time).plusSeconds(60);
    }

    public static Instant slotOf(Instant time) {
        long slotSeconds = SLOT_MINUTES * 60L;
        return Instant.ofEpochSecond(time.getEpochSecond() / slotSeconds * slotSeconds);
    }

    public static Instant nextSlotAfter(Instant time) {
        return slotOf(time).plusSeconds(SLOT_MINUTES * 60L);
    }
}
