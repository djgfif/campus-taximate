package com.campustaximate.server.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(indexes = @Index(name = "idx_party_message_party_id", columnList = "party_id, id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_party_message_client",
                columnNames = {"party_id", "member_id", "client_message_id"}))
@Getter
@NoArgsConstructor
public class PartyMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Party party;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Member member;

    @Column(nullable = false, length = 200)
    private String text;

    /** Stable client-generated key to deduplicate network retries. Nullable for legacy messages. */
    @Column(length = 64)
    private String clientMessageId;

    @Column(nullable = false)
    private Instant createdAt;

    public PartyMessage(Party party, Member member, String text, Instant createdAt) {
        this(party, member, text, createdAt, null);
    }

    public PartyMessage(Party party, Member member, String text, Instant createdAt, String clientMessageId) {
        this.party = party;
        this.member = member;
        this.text = text;
        this.createdAt = createdAt;
        this.clientMessageId = clientMessageId;
    }
}
