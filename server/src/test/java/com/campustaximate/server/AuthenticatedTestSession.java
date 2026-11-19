package com.campustaximate.server;

import com.campustaximate.server.service.LoginTokenService;
import com.campustaximate.server.web.LoginTokenFilter;
import org.springframework.mock.web.MockHttpSession;

/** Uses the same revocable credential as a real login, without bypassing the filter. */
public final class AuthenticatedTestSession {
    private AuthenticatedTestSession() { }
    public static void authenticate(MockHttpSession session, long memberId, LoginTokenService tokens) {
        session.setAttribute("memberId", memberId);
        session.setAttribute(LoginTokenFilter.TOKEN_KEY, tokens.issue(memberId));
    }
}
