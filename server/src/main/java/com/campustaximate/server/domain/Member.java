package com.campustaximate.server.domain;

import com.campustaximate.matching.Gender;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 비밀번호는 없다. 학교 메일로 받은 일회용 코드로 로그인한다. 닉네임과 성별은 첫 로그인 뒤에 넣는다. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Member {
    private boolean admin;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private School school;

    @Column(nullable = false, unique = true)
    private String email;

    private String nickname;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    private int noShowCount;

    private String loginCode;

    private Instant loginCodeExpiresAt;

    private Instant loginCodeSentAt;

    private int loginCodeFailures;

    /** 코드를 다시 받아도 줄지 않는 하루 실패 횟수. */
    private int loginFailuresToday;

    private LocalDate loginFailureDay;

    /** 가입 때 메일 인증 뒤 정한다. PBKDF2 해시로만 저장한다. */
    private String passwordHash;

    public Member(School school, String email) {
        this.school = school;
        this.email = email;
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    public boolean hasProfile() {
        return nickname != null && gender != null;
    }
}
