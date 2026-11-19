package com.campustaximate.server.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** 일행 채팅 한 줄. 시스템 알림은 보낸 사람이 없고, 사진은 본문 대신 파일 경로를 가진다. */
@Entity
@Table(indexes = @Index(name = "idx_party_message_party_id", columnList = "party_id, id"))
@Getter
@NoArgsConstructor
public class PartyMessage {
    public static final String TEXT = "TEXT";
    public static final String IMAGE = "IMAGE";
    public static final String SYSTEM = "SYSTEM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Party party;

    /** 시스템 알림이면 null. */
    @ManyToOne(fetch = FetchType.LAZY)
    private Member member;

    @Column(nullable = false, length = 200)
    private String text;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 10)
    @org.hibernate.annotations.ColumnDefault("'TEXT'")
    private String kind = TEXT;

    private String imagePath;

    @Column(length = 40)
    private String imageType;

    @Column(length = 64)
    private String clientId;

    public PartyMessage(Party party, Member member, String text, Instant createdAt) {
        this.party = party;
        this.member = member;
        this.text = text;
        this.createdAt = createdAt;
    }

    public static PartyMessage text(Party party, Member member, String text, Instant at, String clientId) {
        PartyMessage m = new PartyMessage(party, member, text, at);
        m.clientId = clientId;
        return m;
    }

    public static PartyMessage image(Party party, Member member, String path, String type, Instant at, String clientId) {
        PartyMessage m = new PartyMessage(party, member, "", at);
        m.kind = IMAGE;
        m.imagePath = path;
        m.imageType = type;
        m.clientId = clientId;
        return m;
    }

    public static PartyMessage system(Party party, String text, Instant at) {
        PartyMessage m = new PartyMessage(party, null, text, at);
        m.kind = SYSTEM;
        return m;
    }

    public boolean system() {
        return SYSTEM.equals(kind);
    }
}
