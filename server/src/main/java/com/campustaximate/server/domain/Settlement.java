package com.campustaximate.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    private Party party;

    private long paidAmount;

    /** 보관 기간이 지나 파일을 지우면 null이다. */
    private String receiptPath;

    private String receiptContentType;

    /** 금액을 고칠 때마다 올린다. "보냄"은 화면에서 본 값이 이 값과 같을 때만 저장한다. */
    private int revision;

    /** 24시간 기준 시각. 금액을 고쳐도 바뀌지 않는다. */
    @Column(nullable = false)
    private Instant requestedAt;

    public Settlement(Party party, long paidAmount, String receiptPath, String receiptContentType, Instant requestedAt) {
        this.party = party;
        this.paidAmount = paidAmount;
        this.receiptPath = receiptPath;
        this.receiptContentType = receiptContentType;
        this.requestedAt = requestedAt;
    }
}
