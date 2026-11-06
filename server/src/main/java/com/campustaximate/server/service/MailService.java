package com.campustaximate.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 메일은 가입 인증과 확정, 미성사, 일행 취소에만 쓴다. 실패해도 업무를 막지 않는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final ObjectProvider<JavaMailSender> sender;

    @Value("${spring.mail.host:}")
    private String host;

    @Value("${app.mail-from}")
    private String from;

    /** 트랜잭션 안이면 커밋이 끝난 뒤에 보낸다. */
    public void send(String to, String subject, String text) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver(to, subject, text);
                }
            });
            return;
        }
        deliver(to, subject, text);
    }

    private void deliver(String to, String subject, String text) {
        if (host == null || host.isBlank()) {
            log.info("메일 서버 없음. to={} subject={} text={}", to, subject, text);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            sender.getObject().send(message);
        } catch (MailException e) {
            log.warn("메일 발송 실패: {}", subject, e);
        }
    }
}
