package com.campustaximate.server.service;

import com.campustaximate.matching.Gender;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.School;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.repository.SchoolRepository;
import java.security.SecureRandom;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int CODE_MINUTES = 10;

    private final MemberRepository members;
    private final SchoolRepository schools;
    private final MailService mail;
    private final AppClock clock;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public void signup(String rawEmail, String password, String nickname, Gender gender) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        String domain = email.substring(email.indexOf('@') + 1);
        School school = schools.findByEmailDomain(domain)
                .orElseThrow(() -> ApiException.badRequest("SCHOOL_NOT_FOUND", "등록된 학교 메일이 아닙니다."));

        Member member = members.findByEmail(email).orElse(null);
        if (member != null && member.isVerified()) {
            throw ApiException.conflict("EMAIL_TAKEN", "이미 가입한 메일입니다.");
        }
        if (member == null) {
            member = new Member(school, email, encoder.encode(password), nickname, gender);
        } else {
            member.setPasswordHash(encoder.encode(password));
            member.setNickname(nickname);
            member.setGender(gender);
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        member.setVerificationCode(code);
        member.setVerificationExpiresAt(clock.now().plusSeconds(CODE_MINUTES * 60L));
        members.save(member);
        mail.send(email, "[캠퍼스 택시메이트] 인증 코드", "인증 코드: " + code);
    }

    @Transactional
    public void verify(String rawEmail, String code) {
        Member member = find(rawEmail);
        boolean valid = code.equals(member.getVerificationCode())
                && member.getVerificationExpiresAt() != null
                && clock.now().isBefore(member.getVerificationExpiresAt());
        if (!valid) {
            throw ApiException.badRequest("INVALID_CODE", "인증 코드가 맞지 않거나 만료됐습니다.");
        }
        member.setVerified(true);
        member.setVerificationCode(null);
        member.setVerificationExpiresAt(null);
    }

    @Transactional(readOnly = true)
    public long login(String rawEmail, String password) {
        Member member = members.findByEmail(rawEmail.trim().toLowerCase(Locale.ROOT))
                .filter(m -> encoder.matches(password, m.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "BAD_LOGIN", "메일이나 비밀번호가 맞지 않습니다."));
        if (!member.isVerified()) {
            throw ApiException.forbidden("메일 인증을 먼저 끝내 주세요.");
        }
        return member.getId();
    }

    public String encode(String password) {
        return encoder.encode(password);
    }

    private Member find(String rawEmail) {
        return members.findByEmail(rawEmail.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.badRequest("INVALID_CODE", "인증 코드가 맞지 않거나 만료됐습니다."));
    }
}
