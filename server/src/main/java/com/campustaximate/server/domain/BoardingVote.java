package com.campustaximate.server.domain;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
@Entity @Getter @Setter @NoArgsConstructor
@Table(uniqueConstraints=@UniqueConstraint(name="uk_boarding_vote",columnNames={"case_id","actor_id"}))
public class BoardingVote {
@Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
private Long caseId; private Long actorId; private boolean came; private Instant createdAt;
}
