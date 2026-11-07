package com.campustaximate.server.service;

import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.MailOutbox;
import com.campustaximate.server.repository.MailOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 알림 메일은 outbox에 넣어 별도 작업이 보내고 실패하면 다시 시도한다. 업무 처리는 메일 서버를 기다리지 않는다.
 * 로그인 코드는 비밀값이라 저장하지 않고 커밋 뒤 바로 보낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    static final int MAX_ATTEMPTS = 5;
    private static final Duration KEEP_SENT = Duration.ofDays(7);

    private final ObjectProvider<JavaMailSender> sender;
    private final MailOutboxRepository outbox;
    private final AppClock clock;

    @Value("${spring.mail.host:}")
    private String host;

    @Value("${app.mail-from}")
    private String from;

    /** 메일 서버가 연결돼 있어야 화면을 닫아도 알림이 간다. */
    public boolean enabled() {
        return host != null && !host.isBlank();
    }

    /** 호출한 트랜잭션과 함께 저장한다. 롤백되면 메일도 없다. */
    public void send(String to, String subject, String text) {
        outbox.save(new MailOutbox(to, subject, text, clock.now()));
    }

    /** 트랜잭션 안이면 커밋이 끝난 뒤에 보낸다. 실패하면 사용자가 코드를 다시 받는다. */
    public void sendNow(String to, String subject, String text) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliverQuietly(to, subject, text);
                }
            });
            return;
        }
        deliverQuietly(to, subject, text);
    }

    @Scheduled(fixedDelay = 10_000)
    @Transactional
    public void flush() {
        Instant now = Instant.now();
        for (MailOutbox mail : outbox.findTop20ByStatusAndNextAttemptAtLessThanEqualOrderById(MailOutbox.Status.PENDING, clock.now())) {
            attempt(mail, now);
        }
        outbox.deleteSentBefore(clock.now().minus(KEEP_SENT));
    }

    void attempt(MailOutbox mail, Instant now) {
        try {
            deliver(mail.getRecipient(), mail.getSubject(), mail.getBody());
            mail.setStatus(MailOutbox.Status.SENT);
            mail.setNextAttemptAt(clock.now());
        } catch (MailException e) {
            mail.setAttempts(mail.getAttempts() + 1);
            if (mail.getAttempts() >= MAX_ATTEMPTS) {
                mail.setStatus(MailOutbox.Status.FAILED);
                log.error("메일을 보내지 못해 재시도를 멈춥니다. id={} subject={}", mail.getId(), mail.getSubject(), e);
                return;
            }
            mail.setNextAttemptAt(clock.now().plus(Duration.ofMinutes(1L << mail.getAttempts())));
            log.warn("메일 발송 실패. id={} attempts={}", mail.getId(), mail.getAttempts());
        }
    }

    private void deliverQuietly(String to, String subject, String text) {
        try {
            deliver(to, subject, text);
        } catch (MailException e) {
            log.warn("메일 발송 실패: {}", subject, e);
        }
    }

    private void deliver(String to, String subject, String text) {
        if (!enabled()) {
            log.info("메일 서버 없음. subject={}", subject);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        sender.getObject().send(message);
    }
}
