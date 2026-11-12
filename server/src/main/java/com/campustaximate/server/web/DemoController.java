package com.campustaximate.server.web;

import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.service.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 운영 프로필에는 빈과 매핑 자체가 생기지 않는다. 서비스도 demo 프로필을 재검사한다. */
@RestController @Profile("demo") @RequestMapping("/api/demo") @RequiredArgsConstructor
public class DemoController {
    private final DemoService demo;
    private final DemoScenarioService scenarios;
    private final MemberRepository members;
    private final LoginTokenService tokens;
    @Value("${app.cookie-secure}") private boolean secureCookie;
    public record MatchBody(DemoService.Role role) { }
    public record AdvanceBody(@NotNull @Min(1) @Max(2880) Integer minutes) { }
    public record SampleBody(@Min(1) @Max(3) Integer count) { }
    public record ScenarioBody(@NotNull DemoScenarioService.Scenario scenario,DemoService.Role role) { }
    public record LoginBody(@NotBlank @Pattern(regexp="demo-(?:[fm][1-4]|admin)") String account) { }
    private long actor(HttpSession s) {
        demo.requireEnabled();
        if(s.getAttribute(LoginTokenFilter.SESSION_KEY) instanceof Long id) return id;
        throw ApiException.unauthorized();
    }
    @PostMapping("/login")
    public void login(@Valid @RequestBody LoginBody body,HttpServletRequest request,HttpServletResponse response) {
        demo.requireEnabled();
        var member=members.findByEmail(body.account()+"@daejin.ac.kr").orElseThrow(()->ApiException.notFound("시연 계정이 없습니다."));
        LoginTokenFilter.tokenOf(request).ifPresent(tokens::revoke);
        request.getSession(); request.changeSessionId();
        request.getSession().setAttribute(LoginTokenFilter.SESSION_KEY,member.getId());
        response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(LoginTokenFilter.COOKIE,tokens.issue(member.getId()))
                .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/").maxAge(LoginTokenService.VALID).build().toString());
    }
    @GetMapping("/status") public DemoScenarioService.Progress status(HttpSession s) { return scenarios.status(actor(s)); }
    @PostMapping("/next") public DemoScenarioService.Progress next(HttpSession s) { return scenarios.next(actor(s)); }
    @PostMapping("/scenario") public DemoScenarioService.Progress scenario(@Valid @RequestBody ScenarioBody b,HttpSession s) {
        return scenarios.scenario(actor(s),b.scenario(),b.role()==null?DemoService.Role.PAYER:b.role());
    }
    @PostMapping("/match") public DemoService.MatchOutcome match(@RequestBody(required=false) MatchBody b,HttpSession s) {
        return demo.matchNow(actor(s),b==null||b.role()==null?DemoService.Role.PAYER:b.role());
    }
    @PostMapping("/run") public void run(HttpSession s) { actor(s); demo.runNow(); }
    @PostMapping("/advance") public void advance(@Valid @RequestBody AdvanceBody b,HttpSession s) { actor(s); demo.advance(b.minutes()); }
    @PostMapping("/samples") public Map<String,Integer> samples(@Valid @RequestBody(required=false) SampleBody b,HttpSession s) {
        return Map.of("created",demo.addSamples(actor(s),b==null||b.count()==null?2:b.count()));
    }
    @PostMapping("/act") public void act(HttpSession s) { demo.actAsSamples(actor(s)); }
}
