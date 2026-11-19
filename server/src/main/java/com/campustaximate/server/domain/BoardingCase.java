package com.campustaximate.server.domain;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
@Entity @Getter @Setter @NoArgsConstructor
@Table(uniqueConstraints=@UniqueConstraint(name="uk_boarding_case",columnNames={"party_id","request_id"}))
public class BoardingCase {
@Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
private Long partyId; private Long requestId; private Long memberId;
private String kind; private String status; private Instant openedAt; private Instant deadlineAt;
private Instant decidedAt; private Long decidedBy; @Column(length=2000) private String decisionReason;
private boolean noCompensation;
public boolean pending(){ return "PENDING".equals(status)||"REVIEW".equals(status); }
}
