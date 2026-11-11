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
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_settlement_share", columnNames = {"settlement_id", "member_id"}))
public class SettlementShare {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long settlementId;
    private Long partyId;
    private Long memberId;
    private long ratioUnits;
    private int rideOrder;
    private long amount;
    private boolean payer;
}
