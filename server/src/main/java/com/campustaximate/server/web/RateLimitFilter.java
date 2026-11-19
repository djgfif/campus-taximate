package com.campustaximate.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** IP별 분당 요청 수를 제한한다. 인증 경로는 더 낮은 상한을 쓴다. 서버 한 대 기준이다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final ObjectMapper json;
    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private volatile long minute;

    @Value("${app.rate-limit.enabled}")
    private boolean enabled;

    @Value("${app.rate-limit.auth-per-minute}")
    private int authLimit;

    @Value("${app.rate-limit.api-per-minute}")
    private int apiLimit;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean auth = request.getRequestURI().startsWith("/api/auth/") || request.getRequestURI().equals("/api/demo/login");
        String key = (auth ? "auth:" : "api:") + request.getRemoteAddr();
        if (!allow(key, auth ? authLimit : apiLimit, System.currentTimeMillis() / 60_000)) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            json.writeValue(response.getWriter(), Map.of("code", "TOO_MANY_REQUESTS", "message", "요청이 너무 많습니다. 잠시 뒤 다시 시도해 주세요."));
            return;
        }
        chain.doFilter(request, response);
    }

    boolean allow(String key, int limit, long nowMinute) {
        if (nowMinute != minute) {
            synchronized (this) {
                if (nowMinute != minute) {
                    counts.clear();
                    minute = nowMinute;
                }
            }
        }
        return counts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet() <= limit;
    }
}
