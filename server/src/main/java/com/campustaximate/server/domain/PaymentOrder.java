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
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_payment_obligation", columnNames = {"party_id", "member_id", "purpose"}))
public class PaymentOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version private long version;
    @Column(nullable = false, unique = true) private String orderId;
    @Column(unique = true) private String paymentKey;
    private Long partyId;
    private Long memberId;
    private Long requestId;
    private Long settlementId;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    private PaymentPurpose purpose;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    private PaymentStatus status = PaymentStatus.REQUIRED;
    private long amount;
    private long approvedAmount;
    private long refundedAmount;
    /** 논리 작업이 요청한 누적 환불액. 응답 유실에도 같은 차액을 두 번 만들지 않는다. */
    private long refundTarget;
    private Instant deadlineAt;
    private Instant approvedAt;
    private Instant createdAt;
    /** 기한 판정과 PG 승인 상태는 독립적이다. 제외 뒤 승인되어도 재합류하지 않는다. */
    private Instant excludedAt;
    private Instant lastCheckedAt;
    private String gatewayStatus;
}
