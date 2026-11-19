package com.campustaximate.server.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/** 확정 때 값은 요청의 현재 분담금이나 재매칭으로 덮어쓰지 않는다. */
@Entity @Getter @Setter @NoArgsConstructor
@Table(uniqueConstraints=@UniqueConstraint(name="uk_party_member_term",columnNames={"party_id","request_id"}))
public class PartyMemberTerm {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,updatable=false) private Long partyId;
    @Column(nullable=false,updatable=false) private Long requestId;
    @Column(nullable=false,updatable=false) private Long memberId;
    @Column(nullable=false,updatable=false) private long confirmedShare;
    @Column(nullable=false,updatable=false) private long confirmedPrepay;
    @Column(nullable=false,updatable=false) private boolean migratedUnknown;
    private Instant boardingUnconfirmedAt;
    private Instant exitedAt;
    private String exitReason;
    private boolean refundHeld;
}
