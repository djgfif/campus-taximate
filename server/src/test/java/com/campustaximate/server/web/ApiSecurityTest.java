package com.campustaximate.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class ApiSecurityTest {

    @Autowired private MockMvc mvc;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;

    /** 공개 목록에 없는 API는 전부 로그인을 요구한다. 새 경로가 생겨도 이 테스트가 잡는다. */
    @Test
    void everyPrivateApiRejectsAnonymousRequest() throws Exception {
        List<String> checked = new ArrayList<>();
        for (var info : mappings.getHandlerMethods().keySet()) {
            for (String pattern : info.getPatternValues()) {
                // 시연 계정 빠른 로그인은 demo 프로필의 DemoController에서만 열린다(DemoProfileIsolationTest가 운영 거절을 확인).
                if (!pattern.startsWith("/api/") || AccessInterceptor.PUBLIC_PATHS.contains(pattern) || pattern.equals("/api/demo/login")) {
                    continue;
                }
                String path = pattern.replaceAll("\\{[^}]+}", "1");
                for (var method : info.getMethodsCondition().getMethods()) {
                    var call = request(HttpMethod.valueOf(method.name()), path);
                    info.getConsumesCondition().getConsumableMediaTypes().stream().findFirst().ifPresent(call::contentType);
                    mvc.perform(call).andExpect(status().isUnauthorized());
                    checked.add(method + " " + path);
                }
            }
        }
        assertThat(checked).hasSizeGreaterThan(20);
    }

    @Test
    void writeFromOtherSiteIsRejected() throws Exception {
        mvc.perform(post("/api/auth/code").header("Origin", "https://evil.example").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"demo-f1@daejin.ac.kr\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BAD_ORIGIN"));
    }

    @Test
    void responsesCarrySecurityHeaders() throws Exception {
        var response = mvc.perform(get("/api/config")).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().exists("X-Request-Id")).andReturn().getResponse();
        String csp = response.getHeader("Content-Security-Policy");
        assertThat(csp).contains("https://js.tosspayments.com",
                "connect-src 'self' https://dapi.kakao.com *.daumcdn.net *.kakaocdn.net https://*.tosspayments.com",
                "frame-src 'self' https://*.tosspayments.com");
        assertThat(csp).doesNotContain("script-src *", "connect-src *", "frame-src *");
    }

    @Test
    void brokenJsonReturnsSameErrorShape() throws Exception {
        mvc.perform(post("/api/auth/code").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void invalidCoordinateNamesTheField() throws Exception {
        mvc.perform(post("/api/auth/code").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nope\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.email").exists());
    }

    @Test
    void healthIsPublicAndScreenRoutesOpenTheApp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/history")).andExpect(status().isOk());
    }
}
