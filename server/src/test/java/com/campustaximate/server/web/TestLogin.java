package com.campustaximate.server.web;

import com.campustaximate.server.service.LoginTokenService;
import org.springframework.mock.web.MockHttpSession;

/** 테스트에서 로그인한 세션을 만든다. 실제 로그인처럼 토큰을 발급해 세션에 묶어야 필터가 인정한다. */
public final class TestLogin {

    private TestLogin() {
    }

    public static MockHttpSession session(LoginTokenService tokens, long memberId) {
        MockHttpSession session = new MockHttpSession();
        LoginTokenFilter.bind(session, memberId, tokens.issue(memberId));
        return session;
    }

    /** 이미 있는 세션을 다른 회원으로 로그인시킨다. */
    public static void signIn(MockHttpSession session, LoginTokenService tokens, long memberId) {
        LoginTokenFilter.bind(session, memberId, tokens.issue(memberId));
    }
}
