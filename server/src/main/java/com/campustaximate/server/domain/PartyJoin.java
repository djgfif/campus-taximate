package com.campustaximate.server.domain;
import jakarta.persistence.*;import java.time.Instant;import lombok.*;
/** 입금 전에는 기존 일행의 인원·요금·순서를 바꾸지 않는 합류 예약이다. */
@Entity @Getter @Setter @NoArgsConstructor
public class PartyJoin {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)private Long id;
    private Long partyId;private Long requestId;private Long memberId;@Column(unique=true)private Long paymentOrderId;
    private String state;private Instant offeredAt;private Instant deadlineAt;private long baseGroupVersion;
    private Long payerId;private Instant departureAt;private long totalFare;
}
