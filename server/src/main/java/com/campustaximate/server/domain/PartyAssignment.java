package com.campustaximate.server.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "party_assignment", uniqueConstraints = @UniqueConstraint(columnNames = {"party_id", "member_id"}))
@Getter
@Setter
@NoArgsConstructor
public class PartyAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private Party party;
    private long memberId;
    private long requestId;

    /** Leaving members may read only messages that already existed on departure. */
    private Instant leftAt;
    private Long lastVisibleMessageId;

    public void leave(Instant now, long lastMessageId) {
        if (leftAt == null) {
            leftAt = now;
            lastVisibleMessageId = lastMessageId;
        }
    }
}
