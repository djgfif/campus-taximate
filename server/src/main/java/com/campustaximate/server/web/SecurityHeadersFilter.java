package com.campustaximate.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 보안 헤더를 붙이고, 다른 사이트에서 보낸 쓰기 요청을 막는다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String CONTENT_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self' https://dapi.kakao.com *.daumcdn.net *.kakaocdn.net https://js.tosspayments.com",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob: https: http:",
            "font-src 'self' data:",
            "connect-src 'self' https://dapi.kakao.com *.daumcdn.net *.kakaocdn.net https://*.tosspayments.com",
            "frame-src 'self' https://*.tosspayments.com",
            "object-src 'none'",
            "base-uri 'self'",
            "frame-ancestors 'none'");

    private final ObjectMapper json;

    @Value("${app.public-url}")
    private String publicUrl;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        String path = StrictPathFilter.normalizedPath(request);
        if (!path.startsWith("/swagger-ui") && !path.startsWith("/v3/api-docs")) {
            response.setHeader("Content-Security-Policy", CONTENT_POLICY);
        }
        if (path.startsWith("/api/") && !SAFE_METHODS.contains(request.getMethod())
                && !allowed(request.getHeader("Origin"), request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            json.writeValue(response.getWriter(), Map.of("code", "BAD_ORIGIN", "message", "허용되지 않은 요청입니다."));
            return;
        }
        chain.doFilter(request, response);
    }

    /** 브라우저가 Origin을 보내지 않는 요청은 SameSite 쿠키에 맡긴다. 스킴·호스트·포트를 함께 대조한다. */
    private boolean allowed(String origin, HttpServletRequest request) {
        if (origin == null) {
            return true;
        }
        String normalized = originOf(origin);
        if (normalized == null) {
            return false;
        }
        String configured = originOf(publicUrl);
        if (configured != null && normalized.equals(configured)) {
            return true;
        }
        String scheme = request.getScheme() == null ? "http" : request.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = request.getServerName() == null ? "" : request.getServerName().toLowerCase(java.util.Locale.ROOT);
        int port = effectivePort(scheme, request.getServerPort());
        return !host.isEmpty() && normalized.equals(scheme + "://" + host + ":" + port);
    }

    private static String originOf(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            String s = scheme.toLowerCase(java.util.Locale.ROOT);
            return s + "://" + host.toLowerCase(java.util.Locale.ROOT) + ":" + effectivePort(s, uri.getPort());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int effectivePort(String scheme, int port) {
        if (port > 0) return port;
        return "https".equals(scheme) ? 443 : 80;
    }
}
