package com.campustaximate.server.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 정산 적립금 장부(S43). amount = 이 일행에서 받아 둔 확정 몫 − 결제자에게 돌려준 금액(+면 적립, −면 사용).
 * covered는 확정 몫보다 적게 선결제된 사람의 부족분을 추가 결제 대신 적립금이 메운 금액이다. 실제 송금이 아닌 장부다.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class ReserveEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(unique = true, nullable = false) private Long settlementId;
    @Column(nullable = false) private Long partyId;
    private long amount;
    private long covered;
    private long paidAmount;
    private long fixedTotal;
    @Column(nullable = false) private Instant recordedAt;
}
