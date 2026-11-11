package com.campustaximate.server.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class PaymentOperation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version private long version;
    private Long paymentOrderId;
    @Column(nullable = false, unique = true) private String idempotencyKey;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    private PaymentOperationType type;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    private PaymentOperationStatus status = PaymentOperationStatus.PENDING;
    private long amount;
    private long targetRefund;
    private String reason;
    private int attempts;
    private Instant nextAttemptAt;
    private Instant leaseUntil;
    private Instant completedAt;
    /** 비밀 키·원본 응답 대신 짧은 오류 구분만 저장한다. */
    private String lastError;
}
