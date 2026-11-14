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
/** 실제 송금이 아닌 정산·해산 보전 모의 지급 장부. 해산이면 settlementId는 없다. */
@Table(uniqueConstraints=@UniqueConstraint(name="uk_payout_party",columnNames={"party_id"}))
public class PayoutLedger {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(unique = true) private Long settlementId;
    private Long partyId;
    private Long memberId;
    private long amount;
    private Instant recordedAt;
}
