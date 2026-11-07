package com.campustaximate.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 보낼 알림 메일. 업무 저장과 같은 트랜잭션에 넣어 커밋된 일만 보내고, 실패하면 다시 시도한다. */
@Entity
@Table(indexes = @Index(name = "idx_mail_outbox_due", columnList = "status, nextAttemptAt"))
@Getter
@Setter
@NoArgsConstructor
public class MailOutbox {

    public enum Status { PENDING, SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, length = 2000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    private int attempts;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    public MailOutbox(String recipient, String subject, String body, Instant nextAttemptAt) {
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.nextAttemptAt = nextAttemptAt;
    }
}
