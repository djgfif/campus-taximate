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
public class AccessInterceptor implements HandlerInterceptor {

    static final Set<String> PUBLIC_PATHS = Set.of("/api/config", "/api/me", "/api/auth/code", "/api/auth/login", "/api/auth/logout");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (PUBLIC_PATHS.contains(request.getRequestURI())) {
            return true;
        }
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(LoginTokenFilter.SESSION_KEY) == null) {
            throw ApiException.unauthorized();
        }
        return true;
    }
}
