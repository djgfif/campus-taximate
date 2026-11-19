package com.campustaximate.server.domain;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
@Entity @Getter @Setter @NoArgsConstructor
@Table(uniqueConstraints=@UniqueConstraint(name="uk_ride_confirmation",columnNames={"party_id","member_id"}))
public class RideConfirmation {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long partyId;
    private Long memberId;
    private boolean rode;
    private Instant answeredAt;
}
