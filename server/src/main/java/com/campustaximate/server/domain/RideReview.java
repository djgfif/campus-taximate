package com.campustaximate.server.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 같이 탄 사람에게 남기는 칭찬 한 개. 나쁜 평가는 받지 않고, 문제는 신고로 처리한다. */
@Entity
@Table(name = "ride_review", uniqueConstraints = @UniqueConstraint(name = "uk_ride_review", columnNames = {"party_id", "reviewer_id", "target_id", "tag"}))
@Getter
@Setter
@NoArgsConstructor
public class RideReview {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "party_id", nullable = false)
    private Long partyId;

    @Column(name = "reviewer_id", nullable = false)
    private Long reviewerId;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(nullable = false, length = 20)
    private String tag;

    @Column(nullable = false)
    private Instant createdAt;
}
