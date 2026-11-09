package com.campustaximate.server.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member_report", uniqueConstraints = @UniqueConstraint(columnNames = {"reporter_id", "reported_id", "party_id"}))
@Getter
@Setter
@NoArgsConstructor
public class MemberReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long reporterId;
    private long reportedId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private Party party;
    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ReportReason reason;
    @Column(length = 200)
    private String memo;
    /** Snapshot is independent of the short-lived original chat message. */
    private Long messageId;
    @Column(length = 200)
    private String messageSnapshot;
    private Instant createdAt;
}
