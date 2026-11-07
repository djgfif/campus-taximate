package com.campustaximate.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.MailOutbox;
import com.campustaximate.server.repository.MailOutboxRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

class MailOutboxTest {

    @SuppressWarnings("unchecked")
    private MailService failing() {
        JavaMailSender sender = mock(JavaMailSender.class);
        doThrow(new MailSendException("down")).when(sender).send(any(SimpleMailMessage.class));
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(sender);
        AppClock clock = mock(AppClock.class);
        when(clock.now()).thenReturn(Instant.parse("2026-11-09T00:00:00Z"));
        MailService service = new MailService(provider, mock(MailOutboxRepository.class), clock);
        ReflectionTestUtils.setField(service, "host", "smtp.test");
        ReflectionTestUtils.setField(service, "from", "no@test");
        return service;
    }

    @Test
    void failedMailWaitsLongerThenStops() {
        MailService service = failing();
        Instant start = Instant.parse("2026-11-09T00:00:00Z");
        MailOutbox mail = new MailOutbox("a@test", "제목", "본문", start);

        service.attempt(mail, start);
        assertThat(mail.getStatus()).isEqualTo(MailOutbox.Status.PENDING);
        assertThat(mail.getNextAttemptAt()).isEqualTo(start.plusSeconds(120));

        for (int i = 1; i < MailService.MAX_ATTEMPTS; i++) {
            service.attempt(mail, start);
        }
        assertThat(mail.getStatus()).isEqualTo(MailOutbox.Status.FAILED);
    }
}
