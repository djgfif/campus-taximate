package com.campustaximate.server.domain;

import com.campustaximate.matching.Direction;
import com.campustaximate.matching.GeoPoint;
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

/** 자동으로 매칭된 일행. 하교는 hub, 등교는 school이 있다. 등교의 출발 시각은 첫 승차 시각이다. */
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @ManyToOne(fetch = FetchType.LAZY)
    private Hub hub;

    @ManyToOne(fetch = FetchType.LAZY)
    private School school;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Member payer;

    @Column(nullable = false)
    private Instant departureAt;

    /** 첫 승차자가 "택시 탔어요"를 누른 시각. */
    private Instant boardedAt;

    private long expectedTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartyStatus status = PartyStatus.CONFIRMED;

    public Party(Direction direction, Hub hub, School school, Member payer, Instant departureAt, long expectedTotal) {
        this.direction = direction;
        this.hub = hub;
        this.school = school;
        this.payer = payer;
        this.departureAt = departureAt;
        this.expectedTotal = expectedTotal;
    }

    /** 일행의 공통 지점. 하교는 거점, 등교는 학교다. */
    public GeoPoint anchorPoint() {
        return hub != null ? new GeoPoint(hub.getLat(), hub.getLon()) : new GeoPoint(school.getLat(), school.getLon());
    }

    public String anchorName() {
        return hub != null ? hub.getName() : school.getGateName();
    }
}
