package com.campustaximate.server.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member_restriction")
@Getter
@Setter
@NoArgsConstructor
public class MemberRestriction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long memberId;
    @Column(nullable = false, length = 30)
    private String reason;
    private Instant createdAt;
    private Instant expiresAt;
}
