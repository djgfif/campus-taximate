package com.campustaximate.server.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(indexes = @Index(name = "idx_payment_method_member", columnList = "member_id, active, deleted_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentMethod {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", foreignKey = @ForeignKey(name = "fk_payment_method_member"))
    private Member member;

    @Column(nullable = false, length = 30)
    private String provider;

    @JsonIgnore
    @Convert(converter = AccountNumberConverter.class)
    @Column(nullable = false, length = 512)
    private String billingKey;

    @Column(nullable = false, length = 100)
    private String cardLabel;

    @Column(nullable = false, columnDefinition = "bit")
    private boolean active = true;

    @Column(nullable = false)
    private Instant registeredAt;
    private Instant deletedAt;

    public PaymentMethod(Member member, String provider, String billingKey, String cardLabel, Instant registeredAt) {
        this.member = member;
        this.provider = provider;
        this.billingKey = billingKey;
        this.cardLabel = cardLabel;
        this.registeredAt = registeredAt;
    }

    public void deactivate(Instant now) {
        active = false;
        deletedAt = now;
    }
}
