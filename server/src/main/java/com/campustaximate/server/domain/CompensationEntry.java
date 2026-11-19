package com.campustaximate.server.domain;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
@Entity @Getter @Setter @NoArgsConstructor
@Table(uniqueConstraints={@UniqueConstraint(name="uk_compensation_entry",columnNames={"settlement_id","request_id"}),
    @UniqueConstraint(name="uk_compensation_party_request",columnNames={"party_id","request_id"})})
public class CompensationEntry {
@Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
private Long partyId; private Long settlementId; private Long requestId; private Long memberId;
private long rawShare; private long credit; private long charge; private long netShare;
}
