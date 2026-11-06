package com.campustaximate.server.web;

import com.campustaximate.matching.Gender;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Hub;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.domain.Settlement;
import com.campustaximate.server.repository.HubRepository;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.route.RouteClient;
import com.campustaximate.server.service.AuthService;
import com.campustaximate.server.service.DataSeeder;
import com.campustaximate.server.service.DemoService;
import com.campustaximate.server.service.PartyService;
import com.campustaximate.server.service.RideRequestService;
import com.campustaximate.server.service.SettlementService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    private static final String SESSION_KEY = "memberId";

    private final AuthService auth;
    private final RideRequestService rideRequests;
    private final PartyService parties;
    private final SettlementService settlements;
    private final DemoService demo;
    private final MemberRepository members;
    private final HubRepository hubs;
    private final RideRequestRepository requests;
    private final RouteClient routes;
    private final AppClock clock;

    public record SignupBody(@Email @NotBlank String email, @NotBlank @Size(min = 8, max = 64) String password,
                             @NotBlank @Size(max = 20) String nickname, @NotNull Gender gender) {
    }

    public record VerifyBody(@NotBlank String email, @NotBlank String code) {
    }

    public record LoginBody(@NotBlank String email, @NotBlank String password) {
    }

    public record RequestBodyDto(@NotNull Long hubId, @NotNull Instant windowStart, @NotNull Instant windowEnd,
                                 @NotBlank @Size(max = 100) String dropoffName, @NotNull Double dropoffLat,
                                 @NotNull Double dropoffLon, @Size(max = 100) String memo) {
    }

    public record AmountBody(@NotNull Long amount) {
    }

    public record SentBody(@NotNull Integer revision) {
    }

    public record AdvanceBody(@NotNull Integer minutes) {
    }

    public record SampleBody(Integer count) {
    }

    public record RequestView(long id, RequestStatus status, String hubName, Instant windowStart, Instant windowEnd,
                              String dropoffName, long soloFare, int soloMinutes, String memo) {
        static RequestView of(RideRequest r) {
            return new RequestView(r.getId(), r.getStatus(), r.getHub().getName(), r.getWindowStart(),
                    r.getWindowEnd(), r.getDropoffName(), r.getSoloFare(), (r.getSoloSeconds() + 59) / 60, r.getMemo());
        }
    }

    public record HubView(long id, String name) {
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("demo", demo.enabled(), "simulatedRoute", routes.simulated(), "now", clock.now(),
                "sampleDropoffs", DataSeeder.SAMPLE_DROPOFFS);
    }

    @PostMapping("/auth/signup")
    public void signup(@Valid @RequestBody SignupBody body) {
        auth.signup(body.email(), body.password(), body.nickname(), body.gender());
    }

    @PostMapping("/auth/verify")
    public void verify(@Valid @RequestBody VerifyBody body) {
        auth.verify(body.email(), body.code());
    }

    @PostMapping("/auth/login")
    public void login(@Valid @RequestBody LoginBody body, HttpSession session) {
        session.setAttribute(SESSION_KEY, auth.login(body.email(), body.password()));
    }

    @PostMapping("/auth/logout")
    public void logout(HttpSession session) {
        session.invalidate();
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpSession session) {
        long id = memberId(session);
        Member member = members.findById(id).orElseThrow(ApiException::unauthorized);
        return Map.of("id", id, "nickname", member.getNickname(), "email", member.getEmail(),
                "gender", member.getGender());
    }

    /** 화면이 주기적으로 읽는 내 상태. */
    @GetMapping("/state")
    public Map<String, Object> state(HttpSession session) {
        long id = memberId(session);
        List<RideRequest> mine = requests.findByMemberIdOrderByIdDesc(id);
        Object latest = mine.isEmpty() ? "" : RequestView.of(mine.get(0));
        return Map.of("now", clock.now(), "latestRequest", latest, "parties", parties.openParties(id),
                "restricted", rideRequests.isRestricted(id));
    }

    @GetMapping("/hubs")
    public List<HubView> hubs(HttpSession session) {
        Member member = members.findById(memberId(session)).orElseThrow(ApiException::unauthorized);
        return hubListOf(member);
    }

    private List<HubView> hubListOf(Member member) {
        return hubs.findBySchool(member.getSchool().getId()).stream()
                .map((Hub h) -> new HubView(h.getId(), h.getName()))
                .toList();
    }

    @PostMapping("/requests")
    public Map<String, Long> createRequest(@Valid @RequestBody RequestBodyDto body, HttpSession session) {
        RideRequest created = rideRequests.create(memberId(session), new RideRequestService.NewRequest(body.hubId(),
                body.windowStart(), body.windowEnd(), body.dropoffName(), body.dropoffLat(), body.dropoffLon(),
                body.memo()));
        return Map.of("id", created.getId());
    }

    @DeleteMapping("/requests/{id}")
    public void cancelRequest(@PathVariable long id, HttpSession session) {
        rideRequests.cancel(memberId(session), id);
    }

    @GetMapping("/history")
    public List<RequestView> history(HttpSession session) {
        return requests.findByMemberIdOrderByIdDesc(memberId(session)).stream().map(RequestView::of).toList();
    }

    @GetMapping("/parties/{id}")
    public PartyService.PartyView party(@PathVariable long id, HttpSession session) {
        return parties.view(memberId(session), id);
    }

    @PostMapping("/parties/{id}/arrive")
    public void arrive(@PathVariable long id, HttpSession session) {
        parties.arrive(memberId(session), id);
    }

    @PostMapping(value = "/parties/{id}/settlement", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void submitSettlement(@PathVariable long id, @RequestParam long amount,
                                 @RequestPart("receipt") MultipartFile receipt, HttpSession session) {
        settlements.submit(memberId(session), id, amount, receipt);
    }

    @PutMapping("/parties/{id}/settlement")
    public void correctSettlement(@PathVariable long id, @Valid @RequestBody AmountBody body, HttpSession session) {
        settlements.correct(memberId(session), id, body.amount());
    }

    @GetMapping("/parties/{id}/receipt")
    public ResponseEntity<Resource> receipt(@PathVariable long id, HttpSession session) {
        Settlement settlement = parties.receiptOf(memberId(session), id);
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
