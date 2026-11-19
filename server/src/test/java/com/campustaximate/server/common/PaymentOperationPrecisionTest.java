package com.campustaximate.server.common;

import static org.assertj.core.api.Assertions.assertThat;
import com.campustaximate.server.domain.PaymentOperation;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentOperationPrecisionTest {
    @Test void immediatelyDueWorkNeverRoundsIntoTheFuture() {
        Instant now=Instant.parse("2026-11-10T00:00:00.123456999Z");
        var operation=new PaymentOperation();operation.setNextAttemptAt(now);operation.setLeaseUntil(now.plusSeconds(60));
        ReflectionTestUtils.invokeMethod(operation,"databasePrecision");
        assertThat(operation.getNextAttemptAt()).isEqualTo(Instant.parse("2026-11-10T00:00:00.123456Z")).isBeforeOrEqualTo(now);
        assertThat(operation.getLeaseUntil()).isEqualTo(Instant.parse("2026-11-10T00:01:00.123456Z"));
    }
}
