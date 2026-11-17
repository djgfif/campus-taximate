package com.campustaximate.server.service;

import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.LoginToken;
import com.campustaximate.server.repository.LoginTokenRepository;
import com.campustaximate.server.repository.MemberRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 같은 기기에서 코드를 다시 받지 않게 하는 토큰. 1년 고정이라 재학 확인도 1년에 한 번 다시 된다. */
@Service
@RequiredArgsConstructor
public class LoginTokenService {

    public static final Duration VALID = Duration.ofDays(365);

    private final LoginTokenRepository tokens;
    private final MemberRepository members;
    private final AppClock clock;
    private final SecureRandom random = new SecureRandom();

    /** 토큰 원문을 돌려준다. 원문은 쿠키로만 나간다. */
    @Transactional
    public String issue(long memberId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new LoginToken(members.getReferenceById(memberId), hash(token), clock.now().plus(VALID)));
        return token;
    }

    @Transactional(readOnly = true)
    public Optional<Long> memberOf(String token) {
        return memberOfHash(hash(token));
    }

    /** 세션에는 토큰 원문 대신 해시만 둔다. 그 해시의 토큰이 아직 살아 있는지 본다. */
    @Transactional(readOnly = true)
    public Optional<Long> memberOfHash(String tokenHash) {
        return tokens.findMemberId(tokenHash, clock.now());
    }

    public static String hashOf(String token) {
        return hash(token);
    }

    @Transactional
    public void revoke(String token) {
        tokens.deleteByHash(hash(token));
    }

    @Transactional
    public void revokeAll(long memberId) {
        tokens.deleteByMember(memberId);
    }

    @Transactional
    public void purgeExpired() {
        tokens.deleteExpired(clock.now());
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
