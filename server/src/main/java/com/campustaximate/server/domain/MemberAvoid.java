package com.campustaximate.server.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member_avoid", uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "avoided_member_id"}))
@Getter
@Setter
@NoArgsConstructor
public class MemberAvoid {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long memberId;
    private long avoidedMemberId;
    private Instant createdAt;
}
