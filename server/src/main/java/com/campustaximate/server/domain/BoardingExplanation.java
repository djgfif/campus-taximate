package com.campustaximate.server.domain;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
@Entity @Getter @Setter @NoArgsConstructor
@Table(uniqueConstraints=@UniqueConstraint(name="uk_boarding_explanation",columnNames={"case_id"}))
public class BoardingExplanation {
@Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
private Long caseId; private Long memberId; @Column(length=2000) private String explanation; private Instant createdAt;
}
