package com.campustaximate.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 자동으로 매칭된 일행. 결제자는 마지막 하차자다. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Party {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Hub hub;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Member payer;

    @Column(nullable = false)
    private Instant departureAt;

    private long expectedTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartyStatus status = PartyStatus.CONFIRMED;

    public Party(Hub hub, Member payer, Instant departureAt, long expectedTotal) {
        this.hub = hub;
        this.payer = payer;
        this.departureAt = departureAt;
        this.expectedTotal = expectedTotal;
    }
}
