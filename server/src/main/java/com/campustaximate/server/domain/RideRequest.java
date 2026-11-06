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

/** 탑승 요청. 일행, 하차 순서, 단독 요금 비율, 분담금, 도착 표시를 함께 갖는다. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class RideRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Member member;

    /** 진행 중일 때만 회원 번호를 넣는다. 유일 제약으로 회원당 진행 중 요청 1개를 보장한다. */
    @Column(unique = true)
    private Long activeMemberId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Hub hub;

    @Column(nullable = false)
    private Instant windowStart;

    @Column(nullable = false)
    private Instant windowEnd;

    @Column(nullable = false)
    private String dropoffName;

    private double dropoffLat;

    private double dropoffLon;

    private double soloKm;

    private int soloSeconds;

    private long soloFare;

    private int sharedSeconds;

    private String memo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status = RequestStatus.WAITING;

    @ManyToOne(fetch = FetchType.LAZY)
    private Party party;

    private Integer dropoffOrder;

    private Long ratioUnits;

    private Long expectedShare;

    private boolean arrived;

    /** 일행 정보를 지우고 대기로 되돌린다. */
    public void backToWaiting() {
        clearParty();
        status = RequestStatus.WAITING;
    }

    public void finish(RequestStatus finalStatus) {
        status = finalStatus;
        activeMemberId = null;
    }

    public void clearParty() {
        party = null;
        dropoffOrder = null;
        ratioUnits = null;
        expectedShare = null;
        arrived = false;
    }
}
