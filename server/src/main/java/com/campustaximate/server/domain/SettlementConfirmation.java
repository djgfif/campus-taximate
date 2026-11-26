package com.campustaximate.server.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_settlement_confirm",
        columnNames = {"settlement_id", "member_id", "revision"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementConfirmation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "settlement_id", foreignKey = @ForeignKey(name = "fk_settlement_confirm_settlement"))
    private Settlement settlement;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", foreignKey = @ForeignKey(name = "fk_settlement_confirm_member"))
    private Member member;

    @Column(nullable = false)
    private int revision;

    @Column(nullable = false)
    private Instant confirmedAt;

    public SettlementConfirmation(Settlement settlement, Member member, int revision, Instant confirmedAt) {
        this.settlement = settlement;
        this.member = member;
        this.revision = revision;
        this.confirmedAt = confirmedAt;
    }
}
