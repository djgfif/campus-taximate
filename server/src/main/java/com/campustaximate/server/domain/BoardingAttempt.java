package com.campustaximate.server.domain;
import jakarta.persistence.*;
import lombok.*;
@Entity @Getter @Setter @NoArgsConstructor
@Table(uniqueConstraints=@UniqueConstraint(columnNames={"partyId","memberId","generation"}))
public class BoardingAttempt {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false) private long partyId;
 @Column(nullable=false) private long memberId;
 @Column(nullable=false) private int generation;
 @Column(nullable=false) private int failures;
}
