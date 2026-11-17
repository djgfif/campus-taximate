package com.campustaximate.server.web;

import com.campustaximate.server.service.LoginTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 로그인 유지 쿠키를 매 요청 확인한다. 토큰이 없어지면 그 기기의 세션도 끝낸다. */
@Component
@RequiredArgsConstructor
public class LoginTokenFilter extends OncePerRequestFilter {

    static final String COOKIE = "taximate_login";
    static final String SESSION_KEY = "memberId";

    private final LoginTokenService tokens;

    static Optional<String> tokenOf(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies).filter(c -> COOKIE.equals(c.getName())).map(Cookie::getValue).findFirst();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    /** 세션이 기대는 로그인 토큰의 해시. 모든 기기 로그아웃으로 토큰이 지워지면 이 세션도 끝난다. */
    static final String SESSION_TOKEN = "loginTokenHash";

    /** 로그인한 세션을 그 기기의 토큰에 묶는다. 로그인 쿠키를 빼고 세션 쿠키만 보내도 토큰 폐기를 따르게 된다. */
    public static void bind(HttpSession session, long memberId, String token) {
        session.setAttribute(SESSION_KEY, memberId);
        session.setAttribute(SESSION_TOKEN, LoginTokenService.hashOf(token));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> cookie = tokenOf(request);
        if (cookie.isPresent()) {
            Optional<Long> member = tokens.memberOf(cookie.get());
            if (member.isPresent()) {
                bind(request.getSession(), member.get(), cookie.get());
            } else {
                signOut(request.getSession(false));
            }
        } else {
            HttpSession session = request.getSession(false);
            if (session != null && session.getAttribute(SESSION_KEY) instanceof Long memberId) {
                // 쿠키가 없으면 세션에 묶인 토큰이 아직 같은 회원의 것으로 살아 있는지 확인한다.
                Object hash = session.getAttribute(SESSION_TOKEN);
                boolean alive = hash instanceof String h && tokens.memberOfHash(h).filter(memberId::equals).isPresent();
                if (!alive) {
                    signOut(session);
                }
            }
        }
        chain.doFilter(request, response);
    }

    private static void signOut(HttpSession session) {
        if (session != null) {
            session.removeAttribute(SESSION_KEY);
            session.removeAttribute(SESSION_TOKEN);
        }
    }
}
