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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        tokenOf(request).ifPresent(token -> {
            Optional<Long> member = tokens.memberOf(token);
            if (member.isPresent()) {
                request.getSession().setAttribute(SESSION_KEY, member.get());
            } else {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.removeAttribute(SESSION_KEY);
                }
            }
        });
        chain.doFilter(request, response);
    }
}
