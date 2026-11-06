package com.campustaximate.server.service;

import com.campustaximate.matching.Gender;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.School;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.repository.SchoolRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 학교 메일로 받은 일회용 코드로 로그인한다. 메일 소유 확인이 곧 로그인이다. */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Duration CODE_VALID = Duration.ofMinutes(10);
    private static final Duration RESEND_WAIT = Duration.ofSeconds(60);
    private static final int MAX_FAILURES = 5;

    private final MemberRepository members;
    private final SchoolRepository schools;
    private final MailService mail;
    private final AppClock clock;
    private final SecureRandom random = new SecureRandom();

    /** 코드를 만들어 메일로 보내고 코드를 돌려준다. 호출하는 쪽은 시연 계정에만 이 값을 내보낸다. */
    @Transactional
    public String requestCode(String rawEmail) {
        String email = normalize(rawEmail);
        School school = schools.findByEmailDomain(email.substring(email.indexOf('@') + 1))
                .orElseThrow(() -> ApiException.badRequest("SCHOOL_NOT_FOUND", "등록된 학교 메일이 아닙니다."));
        Member member = members.findByEmail(email).orElseGet(() -> new Member(school, email));
        Instant now = clock.now();
        boolean tooSoon = member.getLoginCodeSentAt() != null
                && now.isBefore(member.getLoginCodeSentAt().plus(RESEND_WAIT));
        if (tooSoon && !DataSeeder.isSeededDemo(email)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_SOON", "코드는 1분에 한 번만 받을 수 있습니다.");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        member.setLoginCode(code);
        member.setLoginCodeExpiresAt(now.plus(CODE_VALID));
        member.setLoginCodeSentAt(now);
        member.setLoginCodeFailures(0);
        members.save(member);
        mail.send(email, "[캠퍼스 택시메이트] 로그인 코드", "로그인 코드: " + code + " (10분 동안 유효)");
        return code;
    }

    /** 실패 횟수를 남겨야 하므로 거절해도 롤백하지 않는다. */
    @Transactional(noRollbackFor = ApiException.class)
    public long login(String rawEmail, String code) {
        Member member = members.findByEmail(normalize(rawEmail)).orElseThrow(AuthService::invalidCode);
        boolean live = member.getLoginCode() != null && clock.now().isBefore(member.getLoginCodeExpiresAt());
        if (!live) {
            throw invalidCode();
        }
        if (!member.getLoginCode().equals(code)) {
            member.setLoginCodeFailures(member.getLoginCodeFailures() + 1);
            if (member.getLoginCodeFailures() >= MAX_FAILURES) {
                clearCode(member);
            }
            throw invalidCode();
        }
        clearCode(member);
        return member.getId();
    }

    @Transactional
    public void completeProfile(long memberId, String nickname, Gender gender) {
        Member member = members.findById(memberId).orElseThrow(ApiException::unauthorized);
        if (member.hasProfile()) {
            throw ApiException.conflict("PROFILE_EXISTS", "이미 프로필을 입력했습니다.");
        }
        member.setNickname(nickname);
        member.setGender(gender);
    }

    private static void clearCode(Member member) {
        member.setLoginCode(null);
        member.setLoginCodeExpiresAt(null);
    }

    private static ApiException invalidCode() {
        return ApiException.badRequest("INVALID_CODE", "코드가 맞지 않거나 만료됐습니다. 코드를 다시 받아 주세요.");
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
