package com.campustaximate.server.web;
import com.campustaximate.server.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 로그인 쿠키 필터 등 MVC 밖의 예상된 인증 거절도 동일한 JSON으로 끝낸다. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE+1) @RequiredArgsConstructor
public class ApiFailureFilter extends OncePerRequestFilter {
    private final ObjectMapper json;
    @Override protected boolean shouldNotFilter(HttpServletRequest r){return !r.getRequestURI().startsWith("/api/");}
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        try{chain.doFilter(r,response);}catch(RuntimeException|ServletException error){
            ApiException auth=null;
            for(Throwable t=error;t!=null;t=t.getCause())if(t instanceof ApiException e && (e.getStatus().value()==401||e.getStatus().value()==403)){auth=e;break;}
            if(auth==null||response.isCommitted())throw error;
            response.resetBuffer();response.setStatus(auth.getStatus().value());response.setCharacterEncoding("UTF-8");response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getWriter(),Map.of("code",auth.getCode(),"message",auth.getMessage()));
        }
    }
}
