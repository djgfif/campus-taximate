package com.campustaximate.server.domain;
import jakarta.persistence.*;import lombok.*;
@Entity @Getter @Setter @NoArgsConstructor
@Table(uniqueConstraints=@UniqueConstraint(name="uk_join_member",columnNames={"join_id","request_id"}))
public class PartyJoinMember {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)private Long id;
    private Long joinId;private Long requestId;private int rideOrder;private int rideSeconds;private long ratioUnits;private long expectedShare;
}
