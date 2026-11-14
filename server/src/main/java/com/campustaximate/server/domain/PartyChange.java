package com.campustaximate.server.domain;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
@Entity @Getter @Setter @NoArgsConstructor
@Table(uniqueConstraints=@UniqueConstraint(name="uk_party_change",columnNames={"party_id","group_version"}))
public class PartyChange {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long partyId;
    private long groupVersion;
    private String reason;
    private Instant changedAt;
}
