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

    @Autowired private com.campustaximate.server.repository.MemberRepository members;
    @Autowired private com.campustaximate.server.service.LoginTokenService tokens;
    @Test void sessionWithoutRevocableCredentialIsRejected() throws Exception {
        var session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute("memberId", members.findByEmail("demo-f1@daejin.ac.kr").orElseThrow().getId());
        mvc.perform(get("/api/state").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void logoutAllRevokesOtherSessionsEvenWithoutCookie() throws Exception {
        long id = members.findByEmail("demo-f1@daejin.ac.kr").orElseThrow().getId();
        var first = new org.springframework.mock.web.MockHttpSession();
        var second = new org.springframework.mock.web.MockHttpSession();
        com.campustaximate.server.AuthenticatedTestSession.authenticate(first, id, tokens);
        com.campustaximate.server.AuthenticatedTestSession.authenticate(second, id, tokens);
        mvc.perform(get("/api/me").session(second)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout-all").session(first)).andExpect(status().isOk());
        mvc.perform(get("/api/state").session(second)).andExpect(status().isUnauthorized());
    }
    @Test void realLoginRotatesSessionAndCredentialSurvivesCookieOmissionUntilRevoked() throws Exception {
        var session = new org.springframework.mock.web.MockHttpSession();
        String original = session.getId();
        var login = mvc.perform(post("/api/auth/signin").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"demo-f2@daejin.ac.kr\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(session.getId()).isNotEqualTo(original);
        assertThat(login.getResponse().getHeader("Set-Cookie")).contains("taximate_login=");
        String token = (String) session.getAttribute(LoginTokenFilter.TOKEN_KEY);
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isOk());
        assertThat(tokens.memberOf(token)).isEmpty();
    }

    @Test void cookieRestorationRotatesPreexistingAnonymousSession() throws Exception {
        long id = members.findByEmail("demo-f3@daejin.ac.kr").orElseThrow().getId();
        var session = new org.springframework.mock.web.MockHttpSession();
        String original = session.getId();
        mvc.perform(get("/api/me").session(session).cookie(new jakarta.servlet.http.Cookie(LoginTokenFilter.COOKIE, tokens.issue(id))))
                .andExpect(status().isOk());
        assertThat(session.getId()).isNotEqualTo(original);
    }
    @Test void invalidCookieCannotFallBackToValidSessionCredential() throws Exception {
        long id = members.findByEmail("demo-f3@daejin.ac.kr").orElseThrow().getId();
        var session = new org.springframework.mock.web.MockHttpSession();
        com.campustaximate.server.AuthenticatedTestSession.authenticate(session, id, tokens);
        mvc.perform(get("/api/state").session(session).cookie(new jakarta.servlet.http.Cookie(LoginTokenFilter.COOKIE, "invalid")))
                .andExpect(status().isUnauthorized());
    }

    @Test void suspiciousPathParametersAndEncodedAsciiAreRejected() throws Exception {
        mvc.perform(get("/api;x=1/config")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PATH"));
        mvc.perform(get("/%61pi/config")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PATH"));
        mvc.perform(get("/api/admin;x=1/audits")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PATH"));
    }

    @Test void crossPortSameHostOriginIsRejected() throws Exception {
        mvc.perform(post("/api/auth/code").header("Origin", "http://localhost:9999").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"demo-f1@daejin.ac.kr\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BAD_ORIGIN"));
    }

    @Test void passwordChangeRequiresOtpReauthAndRevokesOtherDeviceTokens() throws Exception {
        var existing = new org.springframework.mock.web.MockHttpSession();
        mvc.perform(post("/api/auth/signin").session(existing).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"demo-f2@daejin.ac.kr\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk());
        // 비밀번호 로그인 세션만으로는 기존 비밀번호를 덮어쓸 수 없다.
        mvc.perform(post("/api/auth/password").session(existing).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"new-pass-99\"}"))
                .andExpect(status().isForbidden());
        // 메일 코드로 재인증하면 비밀번호 변경이 허용되고, 다른 기기의 기존 토큰은 즉시 폐기된다.
        var reset = new org.springframework.mock.web.MockHttpSession();
        String codeRes = mvc.perform(post("/api/auth/code").session(reset).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"demo-f2@daejin.ac.kr\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String code = new com.fasterxml.jackson.databind.ObjectMapper().readTree(codeRes).get("demoCode").asText();
        mvc.perform(post("/api/auth/login").session(reset).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"demo-f2@daejin.ac.kr\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/password").session(reset).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"demo1234\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/state").session(existing)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/state").session(reset)).andExpect(status().isOk());
    }

    @Test void pushSubscriptionValidatesNestedKeys() throws Exception {
        long id = members.findByEmail("demo-f3@daejin.ac.kr").orElseThrow().getId();
        var session = new org.springframework.mock.web.MockHttpSession();
        com.campustaximate.server.AuthenticatedTestSession.authenticate(session, id, tokens);
        mvc.perform(post("/api/push/subscription").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/abc\",\"keys\":{\"p256dh\":\"\",\"auth\":\"\"}}"))
                .andExpect(status().isBadRequest());
    }
}
