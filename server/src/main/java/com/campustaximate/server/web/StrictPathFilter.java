package com.campustaximate.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * 경로 파라미터(;), 중복 슬래시, 점 세그먼트, ASCII 퍼센트 인코딩(/%61pi 등)을 차단해
 * 보안 필터·인터셉터와 MVC 라우팅 사이의 경로 해석 불일치를 막는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE - 10)
@RequiredArgsConstructor
public class StrictPathFilter extends OncePerRequestFilter {

    private static final Pattern ENCODED_ASCII = Pattern.compile("%[0-7][0-9a-fA-F]");
    private final ObjectMapper json;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String raw = request.getRequestURI();
        if (raw == null || isSuspicious(raw)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            json.writeValue(response.getWriter(), Map.of("code", "BAD_PATH", "message", "허용되지 않은 경로입니다."));
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean isSuspicious(String raw) {
        return raw.indexOf(';') >= 0
                || raw.indexOf('\\') >= 0
                || raw.contains("//")
                || raw.contains("/./")
                || raw.contains("/../")
                || raw.endsWith("/.")
                || raw.endsWith("/..")
                || ENCODED_ASCII.matcher(raw).find();
    }

    static String normalizedPath(HttpServletRequest request) {
        try {
            return ServletRequestPathUtils.parseAndCache(request).pathWithinApplication().value();
        } catch (RuntimeException ignored) {
            return request.getRequestURI() == null ? "" : request.getRequestURI();
        }
    }
}
