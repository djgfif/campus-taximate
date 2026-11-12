package com.campustaximate.server.web;

import com.campustaximate.server.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** API는 기본적으로 로그인을 요구한다. 새 경로를 만들 때 인증을 빠뜨려도 열리지 않는다. */
@Component
@lombok.RequiredArgsConstructor
public class AccessInterceptor implements HandlerInterceptor {

    private final com.campustaximate.server.repository.MemberRepository members;

    static final Set<String> PUBLIC_PATHS = Set.of("/api/config", "/api/me", "/api/auth/code", "/api/auth/login", "/api/auth/signin", "/api/auth/logout");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (PUBLIC_PATHS.contains(request.getRequestURI())) {
            return true;
        }
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(LoginTokenFilter.SESSION_KEY) == null) {
            throw ApiException.unauthorized();
        }
        if((request.getRequestURI().equals("/api/admin") || request.getRequestURI().startsWith("/api/admin/")) && (!(session.getAttribute(LoginTokenFilter.SESSION_KEY) instanceof Long actor) || !members.findById(actor).map(com.campustaximate.server.domain.Member::isAdmin).orElse(false))) throw ApiException.forbidden("관리자만 이용할 수 있어요.");
        return true;
    }
}
