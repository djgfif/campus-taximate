package com.campustaximate.server.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 회원이 일행 채팅을 어디까지 읽었는지. 읽은 위치는 뒤로 가지 않는다. */
@Entity
@Table(name = "party_chat_read", uniqueConstraints = @UniqueConstraint(name = "uk_party_chat_read", columnNames = {"party_id", "member_id"}))
@Getter
@Setter
@NoArgsConstructor
public class PartyChatRead {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "party_id", nullable = false)
    private Long partyId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private Long lastReadId;

    public PartyChatRead(long partyId, long memberId, long lastReadId) {
        this.partyId = partyId;
        this.memberId = memberId;
        this.lastReadId = lastReadId;
    }
}
