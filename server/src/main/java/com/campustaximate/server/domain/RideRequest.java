package com.campustaximate.server.domain;

import jakarta.persistence.EntityListeners;
import jakarta.persistence.OneToMany;
import jakarta.persistence.CascadeType;
import jakarta.persistence.PrePersist;
import java.util.ArrayList;
import java.util.List;
import com.campustaximate.matching.MatchingSettings;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import com.campustaximate.server.service.ChangeListener;
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
import java.time.Duration;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 탑승 요청. 일행, 승하차 순서, 단독 요금 비율, 분담금, 도착 표시를 함께 갖는다. */
@Entity
@EntityListeners(ChangeListener.class)
@Table(indexes = {
        @Index(name = "idx_ride_request_member", columnList = "member_id, id"),
        @Index(name = "idx_ride_request_status", columnList = "status, direction")
})
@Getter
@Setter
@NoArgsConstructor
public class RideRequest {

    private Long pendingJoinId;

    private static final Duration LEAD = Duration.ofMinutes(MatchingSettings.everyMinute().leadMinutes());
    private static final Duration BOARD_GRACE = Duration.ofMinutes(3);

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    /** 하교만 값이 있다. */
    @ManyToOne(fetch = FetchType.LAZY)
    private Hub hub;

    /** 등교만 값이 있다. */
    @ManyToOne(fetch = FetchType.LAZY)
    private School school;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestMode mode = RequestMode.RESERVATION;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TimeBasis timeBasis = TimeBasis.DEPART;

    private int earlyArrivalMinutes = 10;
    private Double originLat;
    private Double originLon;
    private String originName;

    @Column(nullable = false)
    private Instant requestedAt;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RideRequestPoint> points = new ArrayList<>();

    @PrePersist
    void stampRequest() {
        if (requestedAt == null) requestedAt = Instant.now();
    }

    /** 옛 데이터의 출발지는 단일 승차 지점이다. */
    public GeoPoint origin() {
        if (originLat != null && originLon != null) return new GeoPoint(originLat, originLon);
        return hub != null ? new GeoPoint(hub.getLat(), hub.getLon()) : point();
    }

    public Instant earliestArrival() {
        return arriveBy == null ? null : arriveBy.minusSeconds(earlyArrivalMinutes * 60L);
    }

    private Instant windowStart;

    private Instant windowEnd;

    /** 등교의 도착 희망 시각. */
    private Instant arriveBy;

    /** 등교의 마지막 매칭 슬롯. */
    private Instant deadlineSlot;

    /** 등교 확정 때 정해지는 내 승차 시각. */
    private Instant boardAt;

    /** 하교의 하차 지점이나 등교의 승차 지점. */
    @Column(nullable = false)
    private String pointName;

    private double pointLat;

    private double pointLon;

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

    private Integer rideOrder;

    private Long ratioUnits;

    private Long expectedShare;

    private boolean arrived;
    /** 도착 약속이며 실제 탑승 확인은 아니다. 도착 표시 때 지운다. */
    private Instant nearbyAt;

    /** 등교에서 승차 시각 3분 뒤까지 도착 표시가 없었다. 마지막 판정 때 일행에서 빠진다. */
    private boolean noShow;

    /** 노쇼로 기록된 시각. 최근 30일 노쇼 횟수를 셀 때 쓴다. */
    private Instant noShowAt;

    @Enumerated(EnumType.STRING)
    private EndReason endReason;

    /** 승차 10분 전 안내를 보낸 시각. 두 번 보내지 않는다. */
    private Instant remindedAt;

    public GeoPoint point() {
        return new GeoPoint(pointLat, pointLon);
    }

    /** 결과를 늦어도 언제 알 수 있는지. 이 슬롯이 마지막 매칭 기회다. */
    public Instant resultBy() {
        if(mode==RequestMode.INSTANT) return deadlineSlot!=null?deadlineSlot:requestedAt.plusSeconds(MatchingSettings.everyMinute().instantMatchMinutes()*60L);
        return deadlineSlot != null ? deadlineSlot : windowEnd.minus(LEAD);
    }

    /** 이 시각까지 도착을 표시해야 한다. 하교는 출발 시각, 등교는 내 승차 시각 3분 뒤다. */
    public Instant arriveDeadline() {
        return boardAt != null ? boardAt.plus(BOARD_GRACE) : party.getDepartureAt();
    }

    /** 내가 타는 시각. 하교는 일행 출발, 등교는 내 승차 시각이다. */
    public Instant startAt() {
        return boardAt != null ? boardAt : party.getDepartureAt();
    }

    /** 내가 타기 전까지만 뺄 수 있다. 택시에 탄 결제자는 뺄 수 없다. */
    public boolean canCancelAt(Instant now) {
        boolean boardedPayer = party.getBoardedAt() != null && party.getPayer().getId().equals(member.getId());
        return status == RequestStatus.MATCHED && now.isBefore(startAt()) && !boardedPayer;
    }

    /** 노쇼는 일행 재계산 전이라도 새 요청을 막지 않는다. 일행에서는 마지막 판정 때 빠진다. */
    public void markNoShow(Instant at) {
        noShow = true;
        noShowAt = at;
        activeMemberId = null;
    }

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
        if (noShow) {
            endReason = EndReason.NO_SHOW;
        }
        party = null;
        rideOrder = null;
        boardAt = null;
        ratioUnits = null;
        expectedShare = null;
        arrived = false;
        nearbyAt = null;
        noShow = false;
    }
}
