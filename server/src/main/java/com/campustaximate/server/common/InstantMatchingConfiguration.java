package com.campustaximate.server.common;

import com.campustaximate.matching.MatchingSettings;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.validation.annotation.Validated;

/** 서버·엔진·화면이 같은 즉시 매칭 설정을 사용한다. */
@Configuration @ConfigurationProperties(prefix="app.matching") @Validated @Getter @Setter
public class InstantMatchingConfiguration {
    @Min(1) private int instantMatchMinutes=5;
    @Min(1) private int instantPrepMinutes=5;
    @Min(1) private int instantDispatchMinutes=5;
    @Min(0) private int instantMaxWalkSeconds=300;
    @Bean public MatchingSettings matchingSettings() {
        var d=MatchingSettings.everyMinute();
        return new MatchingSettings(d.maxDelayMinutes(),d.minSavingRate(),d.maxCandidates(),d.maxGroupSize(),d.maxVerifications(),
                d.leadMinutes(),d.slotMinutes(),d.reservationSlotMinutes(),d.earlyConfirmMinutes(),d.searchMillis(),
                instantMatchMinutes,instantPrepMinutes,instantDispatchMinutes);
    }
}
