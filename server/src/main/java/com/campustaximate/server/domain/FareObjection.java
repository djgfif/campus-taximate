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
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_fare_objection", columnNames = {"settlement_id", "member_id"}))
public class FareObjection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long settlementId;
    private Long memberId;
    @Column(length = 500) private String reason;
    private Instant createdAt;
}
