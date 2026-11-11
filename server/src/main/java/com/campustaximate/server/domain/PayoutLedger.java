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
/** 실제 송금이 아닌 결제자 모의 지급 장부. */
public class PayoutLedger {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(unique = true) private Long settlementId;
    private Long partyId;
    private Long memberId;
    private long amount;
    private Instant recordedAt;
}
