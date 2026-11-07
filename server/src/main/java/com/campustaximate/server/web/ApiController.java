package com.campustaximate.server.web;

import com.campustaximate.server.service.PushService;
import com.campustaximate.server.service.MemberSafetyService;
import com.campustaximate.server.domain.ReportReason;
import org.springframework.web.bind.annotation.PutMapping;
import com.campustaximate.server.service.FareAccuracyService;
import com.campustaximate.server.service.ChangeBus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.campustaximate.matching.Direction;
import com.campustaximate.matching.Gender;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.EndReason;
import com.campustaximate.server.domain.Hub;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.domain.School;
import com.campustaximate.server.domain.Settlement;
import com.campustaximate.server.repository.HubRepository;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.route.RouteClient;
import com.campustaximate.server.service.AuthService;
import com.campustaximate.server.service.DataSeeder;
import com.campustaximate.server.service.DemoService;
import com.campustaximate.server.service.RideHistoryService;
import com.campustaximate.server.service.WaitingInsightService;
import com.campustaximate.server.service.LoginTokenService;
import com.campustaximate.server.service.MailService;
import com.campustaximate.server.service.PartyService;
import com.campustaximate.server.service.RideRequestService;
import com.campustaximate.server.service.SettlementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private static final String SESSION_KEY = LoginTokenFilter.SESSION_KEY;

    private final AuthService auth;
    private final LoginTokenService tokens;
    private final RideRequestService rideRequests;
    private final PartyService parties;
    private final SettlementService settlements;
    private final DemoService demo;
    private final WaitingInsightService insights;
    private final RideHistoryService history;
    private final MailService mail;
    private final MemberRepository members;
    private final HubRepository hubs;
    private final RideRequestRepository requests;
    private final RouteClient routes;
    private final AppClock clock;
    private final ChangeBus changes;
    private final PushService push;
    private final FareAccuracyService accuracy;
    private final MemberSafetyService safety;

    @Value("${app.cookie-secure}")
    private boolean secureCookie;

    public record CodeBody(@Email @NotBlank String email) {
    }

    public record LoginBody(@NotBlank String email, @NotBlank String code) {
    }

    public record SignInBody(@NotBlank String email, @NotBlank String password) {
    }

    public record PasswordBody(@NotBlank @Size(min = 8, max = 64) String password) {
    }

    public record ProfileBody(@NotBlank @Size(max = 20) String nickname, @NotNull Gender gender) {
    }

    public record MeView(long id, String email, String nickname, Gender gender, boolean needsPassword, boolean needsProfile) {
    }

    public record RequestBodyDto(@NotNull Direction direction, Long hubId, Instant windowStart, Instant windowEnd,
                                 Instant arriveBy, @NotBlank @Size(max = 100) String pointName,
                                 @NotNull @DecimalMin("-90") @DecimalMax("90") Double pointLat,
                                 @NotNull @DecimalMin("-180") @DecimalMax("180") Double pointLon, @Size(max = 100) String memo) {
        RideRequestService.NewRequest toInput() {
            return new RideRequestService.NewRequest(direction, hubId, windowStart, windowEnd, arriveBy, pointName,
                    pointLat, pointLon, memo);
        }
    }

    public record SentBody(@NotNull Integer revision) {
    }

    public record AdvanceBody(@NotNull Integer minutes) {
    }

    public record SampleBody(Integer count) {
    }

    public record RequestView(long id, RequestStatus status, Direction direction, Long hubId, String anchorName,
                              Instant windowStart, Instant windowEnd, Instant arriveBy, Instant resultBy,
                              String pointName, double pointLat, double pointLon, long soloFare, int soloMinutes,
                              String memo, WaitingInsightService.Insight insight,
                              RideHistoryService.Stage stage, Long paid, EndReason endReason,
                              double anchorLat, double anchorLon, Long partyPaid, Integer partySize, Long partyId, List<Long> partyIds) {
        RequestView withPartyIds(List<Long> ids) {
            return new RequestView(id, status, direction, hubId, anchorName, windowStart, windowEnd, arriveBy,
                    resultBy, pointName, pointLat, pointLon, soloFare, soloMinutes, memo, insight, stage, paid,
                    endReason, anchorLat, anchorLon, partyPaid, partySize, partyId, ids.isEmpty() ? partyIds : ids);
        }

        static RequestView of(RideRequest r) {
            return of(r, null, null, null);
        }

        static RequestView of(RideHistoryService.Entry e) {
            return of(e.request(), null, e.stage(), e.paid(), e.partyPaid(), e.partySize());
        }

        static RequestView of(RideRequest r, WaitingInsightService.Insight insight,
                              RideHistoryService.Stage stage, Long paid) {
            return of(r, insight, stage, paid, null, null);
        }

        private static RequestView of(RideRequest r, WaitingInsightService.Insight insight,
                                      RideHistoryService.Stage stage, Long paid, Long partyPaid, Integer partySize) {
            String anchor = r.getHub() != null ? r.getHub().getName() : r.getSchool().getGateName();
            return new RequestView(r.getId(), r.getStatus(), r.getDirection(),
                    r.getHub() == null ? null : r.getHub().getId(), anchor, r.getWindowStart(), r.getWindowEnd(),
                    r.getArriveBy(), r.resultBy(), r.getPointName(), r.getPointLat(), r.getPointLon(),
                    r.getSoloFare(), (r.getSoloSeconds() + 59) / 60, r.getMemo(), insight, stage, paid,
                    r.isNoShow() ? EndReason.NO_SHOW : r.getEndReason(),
                    r.getHub() != null ? r.getHub().getLat() : r.getSchool().getLat(),
                    r.getHub() != null ? r.getHub().getLon() : r.getSchool().getLon(), partyPaid, partySize, r.getParty() == null ? null : r.getParty().getId(),
                    r.getParty() == null ? List.of() : List.of(r.getParty().getId()));
        }
    }

    public record SchoolView(long id, String name, String gateName, double lat, double lon) {
    }

    public record HubView(long id, String name, double lat, double lon) {
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("demo", demo.enabled(), "simulatedRoute", routes.simulated(), "now", clock.now(),
                "mailEnabled", mail.enabled(), "sampleDropoffs", DataSeeder.SAMPLE_DROPOFFS);
    }

    @PostMapping("/auth/code")
    public Map<String, String> requestCode(@Valid @RequestBody CodeBody body) {
        String code = auth.requestCode(body.email());
        boolean showCode = demo.enabled() && DataSeeder.isSeededDemo(body.email().trim().toLowerCase(Locale.ROOT));
        return showCode ? Map.of("demoCode", code) : Map.of();
    }

    @PostMapping("/auth/login")
    public void login(@Valid @RequestBody LoginBody body, HttpServletRequest request, HttpServletResponse response) {
        startSession(auth.login(body.email(), body.code()), request, response);
    }

    @PostMapping("/auth/signin")
    public void signIn(@Valid @RequestBody SignInBody body, HttpServletRequest request, HttpServletResponse response) {
        startSession(auth.signIn(body.email(), body.password()), request, response);
    }

    @PostMapping("/auth/password")
    public void password(@Valid @RequestBody PasswordBody body, HttpSession session) {
        auth.setPassword(memberId(session), body.password());
    }

    private void startSession(long id, HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession();
        request.changeSessionId();
        session.setAttribute(SESSION_KEY, id);
        response.addHeader(HttpHeaders.SET_COOKIE, loginCookie(tokens.issue(id), LoginTokenService.VALID).toString());
    }

    @PostMapping("/auth/profile")
    public void profile(@Valid @RequestBody ProfileBody body, HttpSession session) {
        auth.completeProfile(memberId(session), body.nickname(), body.gender());
    }

    @PostMapping("/auth/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response, HttpSession session) {
        LoginTokenFilter.tokenOf(request).ifPresent(tokens::revoke);
        endSession(response, session);
    }

    @PostMapping("/auth/logout-all")
    public void logoutAll(HttpServletResponse response, HttpSession session) {
        tokens.revokeAll(memberId(session));
        endSession(response, session);
    }

    private void endSession(HttpServletResponse response, HttpSession session) {
        session.invalidate();
        response.addHeader(HttpHeaders.SET_COOKIE, loginCookie("", Duration.ZERO).toString());
    }

    private ResponseCookie loginCookie(String value, Duration maxAge) {
        return ResponseCookie.from(LoginTokenFilter.COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    @GetMapping("/me")
    public MeView me(HttpSession session) {
        long id = memberId(session);
        Member member = members.findById(id).orElseThrow(ApiException::unauthorized);
        return new MeView(id, member.getEmail(), member.getNickname(), member.getGender(), !member.hasPassword(), !member.hasProfile());
    }

    /** 내 상태가 바뀌면 신호를 보낸다. 연결이 안 되는 환경에서는 화면이 주기적으로 읽는다. */
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(HttpSession session) {
        return changes.subscribe(memberId(session));
    }

    public record PushBody(@NotBlank @Size(max = 500) String endpoint, @NotNull PushKeys keys) {
    }

    public record PushKeys(@NotBlank @Size(max = 200) String p256dh, @NotBlank @Size(max = 100) String auth) {
    }

    @GetMapping("/push/key")
    public Map<String, Object> pushKey() {
        return push.enabled() ? Map.of("publicKey", push.publicKey()) : Map.of();
    }

    @PostMapping("/push/subscription")
    public void subscribe(@Valid @RequestBody PushBody body, HttpSession session) {
        push.subscribe(memberId(session), body.endpoint(), body.keys().p256dh(), body.keys().auth());
    }

    @DeleteMapping("/push/subscription")
    public void unsubscribe(@Valid @RequestBody PushBody body, HttpSession session) {
        push.unsubscribe(memberId(session), body.endpoint());
    }

    /** 정산이 들어온 일행 전체에서 예상 요금이 15% 이내였던 비율. */
    @GetMapping("/stats/fare-accuracy")
    public FareAccuracyService.Accuracy fareAccuracy() {
        return accuracy.measure();
    }

    /** 화면이 읽는 내 상태. */
    @GetMapping("/state")
    public Map<String, Object> state(HttpSession session) {
        long id = memberId(session);
        Object latest = requests.findFirstByMemberIdOrderByIdDesc(id)
                .<Object>map(r -> RequestView.of(r, insights.of(r.getId()), null, null)).orElse("");
        return Map.of("now", clock.now(), "latestRequest", latest, "parties", parties.openParties(id),
                "restricted", rideRequests.isRestricted(id),
                "restriction", java.util.Optional.ofNullable(safety.activeRestriction(id)).<Object>map(r -> r).orElse(""),
                "transferRestricted", rideRequests.isTransferRestricted(id),
                "settledParty", java.util.Optional.ofNullable(parties.latestSettled(id)).<Object>map(p -> p).orElse(""));
    }

    public record ReportBody(@NotNull ReportReason reason, @Size(max = 200) String memo) { }

    @PostMapping("/parties/{partyId}/members/{memberId}/report")
    public void report(@PathVariable long partyId, @PathVariable long memberId,
                       @Valid @RequestBody ReportBody body, HttpSession session) {
        safety.report(memberId(session), memberId, partyId, body.reason(), body.memo());
    }

    @PutMapping("/members/{memberId}/avoid")
    public void avoid(@PathVariable long memberId, HttpSession session) {
        safety.avoid(memberId(session), memberId);
    }

    @DeleteMapping("/members/{memberId}/avoid")
    public void unavoid(@PathVariable long memberId, HttpSession session) {
        safety.unavoid(memberId(session), memberId);
    }

    @GetMapping("/avoids")
    public List<MemberSafetyService.Peer> avoids(HttpSession session) {
        return safety.avoids(memberId(session));
    }

    @GetMapping("/parties/{partyId}/members")
    public List<MemberSafetyService.Peer> partyMembers(
            @PathVariable long partyId, HttpSession session) {
        return safety.peers(memberId(session), partyId);
    }

    @GetMapping("/hubs")
    public List<HubView> hubs(HttpSession session) {
        Member member = members.findById(memberId(session)).orElseThrow(ApiException::unauthorized);
        return hubListOf(member);
    }

    @GetMapping("/school")
    public SchoolView school(HttpSession session) {
        Member member = members.findWithSchoolById(memberId(session)).orElseThrow(ApiException::unauthorized);
        School school = member.getSchool();
        return new SchoolView(school.getId(), school.getName(), school.getGateName(), school.getLat(), school.getLon());
    }

    private List<HubView> hubListOf(Member member) {
        return hubs.findBySchool(member.getSchool().getId()).stream()
                .map((Hub h) -> new HubView(h.getId(), h.getName(), h.getLat(), h.getLon()))
                .toList();
    }

    @PostMapping("/requests")
    public Map<String, Long> createRequest(@Valid @RequestBody RequestBodyDto body, HttpSession session) {
        RideRequest created = rideRequests.create(memberId(session), body.toInput());
        return Map.of("id", created.getId());
    }

    @PostMapping("/requests/preview")
    public RideRequestService.Preview previewRequest(@Valid @RequestBody RequestBodyDto body, HttpSession session) {
        return rideRequests.preview(memberId(session), body.toInput());
    }

    @DeleteMapping("/requests/{id}")
    public void cancelRequest(@PathVariable long id, HttpSession session) {
        rideRequests.cancel(memberId(session), id);
    }

    @GetMapping("/history")
    public List<RequestView> history(HttpSession session) {
        return history.of(memberId(session)).stream()
                .map(e -> RequestView.of(e).withPartyIds(safety.partyIds(e.request().getId()))).toList();
    }

    @GetMapping("/parties/{id}")
    public PartyService.PartyView party(@PathVariable long id, HttpSession session) {
        return parties.view(memberId(session), id);
    }

    @GetMapping("/parties/{id}/routes")
    public PartyService.RouteView partyRoutes(@PathVariable long id, HttpSession session) {
        return parties.routeView(parties.routePlan(memberId(session), id));
    }

    @PostMapping("/parties/{id}/arrive")
    public void arrive(@PathVariable long id, HttpSession session) {
        parties.arrive(memberId(session), id);
    }

    @PostMapping("/parties/{id}/boarded")
    public void boarded(@PathVariable long id, HttpSession session) {
        parties.board(memberId(session), id);
    }

    @GetMapping("/parties/{id}/settlement/preview")
    public List<SettlementService.ShareView> previewSettlement(@PathVariable long id, @RequestParam long amount,
                                                               HttpSession session) {
        return settlements.preview(memberId(session), id, amount);
    }

    @PostMapping(value = "/parties/{id}/settlement", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void submitSettlement(@PathVariable long id, @RequestParam long amount,
                                 @RequestParam(required = false) String bankName,
                                 @RequestParam(required = false) String accountNumber,
                                 @RequestPart("receipt") MultipartFile receipt, HttpSession session) {
        settlements.submit(memberId(session), id, amount, receipt, bankName, accountNumber);
    }

    @PostMapping(value = "/parties/{id}/settlement/correction", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void correctSettlement(@PathVariable long id, @RequestParam long amount,
                                  @RequestParam(required = false) String bankName,
                                  @RequestParam(required = false) String accountNumber,
                                  @RequestPart("receipt") MultipartFile receipt, HttpSession session) {
        settlements.correct(memberId(session), id, amount, receipt, bankName, accountNumber);
    }

    @GetMapping("/parties/{id}/receipt")
    public ResponseEntity<Resource> receipt(@PathVariable long id, HttpSession session) {
        Settlement settlement = parties.receiptOf(memberId(session), id);
        if (settlement.getReceiptPath() == null) {
            throw ApiException.notFound("보관 기간이 지나 사진이 삭제됐습니다.");
        }
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(settlement.getReceiptContentType()))
                .body(new FileSystemResource(Path.of(settlement.getReceiptPath())));
    }

    @PostMapping("/transfers/{id}/sent")
    public void sent(@PathVariable long id, @Valid @RequestBody SentBody body, HttpSession session) {
        settlements.markSent(memberId(session), id, body.revision());
    }

    @PostMapping("/transfers/{id}/received")
    public void received(@PathVariable long id, HttpSession session) {
        settlements.markReceived(memberId(session), id);
    }

    @PostMapping("/demo/run")
    public void demoRun(HttpSession session) {
        memberId(session);
        demo.runNow();
    }

    @PostMapping("/demo/match")
    public DemoService.MatchOutcome demoMatch(HttpSession session) {
        return demo.matchNow(memberId(session));
    }

    @PostMapping("/demo/advance")
    public void demoAdvance(@Valid @RequestBody AdvanceBody body, HttpSession session) {
        memberId(session);
        demo.advance(body.minutes());
    }

    @PostMapping("/demo/samples")
    public Map<String, Integer> demoSamples(@RequestBody(required = false) SampleBody body, HttpSession session) {
        int count = body == null || body.count() == null ? 2 : body.count();
        return Map.of("created", demo.addSamples(memberId(session), count));
    }

    @PostMapping("/demo/act")
    public void demoAct(HttpSession session) {
        demo.actAsSamples(memberId(session));
    }

    private long memberId(HttpSession session) {
        Object id = session.getAttribute(SESSION_KEY);
        if (id == null) {
            throw ApiException.unauthorized();
        }
        return (Long) id;
    }
}
