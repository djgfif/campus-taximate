package com.campustaximate.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.repository.PartyRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.repository.SettlementRepository;
import com.campustaximate.server.repository.TransferRequestRepository;
import com.campustaximate.server.service.DemoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@TestPropertySource(properties = "app.upload-dir=target/test-uploads")
class FlowTest {

    private static final double[][] POINTS = {{37.785, 127.105}, {37.745, 127.075}, {37.700, 127.050}};

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private AppClock clock;
    @Autowired private DemoService demo;
    @Autowired private com.campustaximate.server.service.PaymentService payments;
    @Autowired private com.campustaximate.server.repository.PaymentOrderRepository paymentOrders;
    @Autowired private com.campustaximate.server.repository.PaymentOperationRepository paymentOperations;
    @Autowired private com.campustaximate.server.repository.SettlementShareRepository allocations;
    @Autowired private com.campustaximate.server.repository.PayoutLedgerRepository payouts;
    @Autowired private MemberRepository members;
    @Autowired private RideRequestRepository requests;
    @Autowired private PartyRepository parties;
    @Autowired private SettlementRepository settlements;
    @Autowired private TransferRequestRepository transfers;
    @Autowired private com.campustaximate.server.repository.LoginTokenRepository loginTokens;
    @org.springframework.boot.test.mock.mockito.SpyBean private com.campustaximate.server.service.MailService mail;
    @org.springframework.boot.test.mock.mockito.SpyBean private com.campustaximate.server.route.RouteClient routes;

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private long hubId;

    @BeforeEach
    void reset() throws Exception {
        PaymentTestSupport.clear(jdbc);
        transfers.deleteAll();
        settlements.deleteAll();
        requests.deleteAll();
        parties.deleteAll();
        members.findAll().forEach(m -> {
            m.setNoShowCount(0);
            members.save(m);
        });
        clock.moveTo(AppClock.slotOf(Instant.now()).plusSeconds(60));
        JsonNode hubs = read(mvc.perform(get("/api/hubs").session(login("demo-f1"))).andReturn().getResponse().getContentAsString());
        hubId = hubs.get(0).get("id").asLong();
    }

    @Test
    void memberCanCompareRoutesAndConfirmedMetrics() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToConfirmation();
        JsonNode party = onlyParty(users.get(0));
        long id = party.get("id").asLong();
        JsonNode routes = read(mvc.perform(get("/api/parties/" + id + "/routes").session(users.get(0)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(routes.get("shared")).hasSize(4);
        assertThat(routes.get("solo")).hasSize(2);
        for (JsonNode rider : party.get("members")) {
            assertThat(rider.get("soloFare").asLong()).isPositive();
            assertThat(rider.get("soloSeconds").asInt()).isPositive();
            assertThat(rider.get("sharedSeconds").asInt()).isPositive();
        }
    }

    @Test
    void fullFlowFromRequestToCompleted() throws Exception {
        List<MockHttpSession> users = departedParty();
        MockHttpSession payer = payerOf(users);
        long id = onlyParty(payer).get("id").asLong();
        long total = onlyParty(payer).get("expectedTotal").asLong();
        submit(id, total, payer).andExpect(status().isOk());
        assertThat(onlyParty(payer).get("status").asText()).isEqualTo("OBJECTION_WINDOW");
        assertThat(transfers.count()).isZero();
        assertThat(payouts.count()).isZero();
        advance(24 * 60 + 1);
        assertThat(parties.findById(id).orElseThrow().getStatus()).isEqualTo(com.campustaximate.server.domain.PartyStatus.COMPLETED);
        assertThat(payouts.findAll()).hasSize(1);
        assertThat(allocations.findAll().stream().mapToLong(a -> a.getAmount()).sum()).isEqualTo(total);
        assertThat(state(payer).get("parties")).isEmpty();
    }

    @Test
    void cancelDuringRouteLookupDoesNotUndoCompletedRide() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToConfirmation();
        JsonNode party = onlyParty(users.get(0));
        long partyId = party.get("id").asLong();
        long requestId = party.get("myRequestId").asLong();
        for (MockHttpSession user : users) {
            mvc.perform(post("/api/parties/" + partyId + "/arrive").session(user)).andExpect(status().isOk());
        }
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(call -> {
            if (once.compareAndSet(false, true)) {
                advance(30);
            }
            return call.callRealMethod();
        }).when(routes).route(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        mvc.perform(delete("/api/requests/" + requestId).session(users.get(0))).andExpect(status().isConflict());

        assertThat(once).isTrue();
        var riders = requests.findByPartyOrderByRideOrder(parties.findById(partyId).orElseThrow());
        assertThat(riders).hasSize(3);
        assertThat(riders).allMatch(r -> r.getStatus() == com.campustaximate.server.domain.RequestStatus.COMPLETED);
    }

    @Test
    void cancelBeforeDepartureShrinksPartyAndRecalculates() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToConfirmation();
        JsonNode before = onlyParty(users.get(1));
        long requestId = onlyParty(users.get(0)).get("myRequestId").asLong();

        mvc.perform(delete("/api/requests/" + requestId).session(users.get(0))).andExpect(status().isOk());

        assertThat(state(users.get(0)).get("latestRequest").get("status").asText()).isEqualTo("CANCELED");
        assertThat(state(users.get(0)).get("parties")).isEmpty();
        JsonNode after = onlyParty(users.get(1));
        assertThat(after.get("members")).hasSize(2);
        assertThat(after.get("members").get(0).get("rideOrder").asInt()).isEqualTo(1);
        assertThat(after.get("id").asLong()).isEqualTo(before.get("id").asLong());
        var remaining = requests.findByPartyOrderByRideOrder(parties.findById(after.get("id").asLong()).orElseThrow());
        var weights = remaining.stream().map(r -> r.getSoloFare()).toList();
        var shares = com.campustaximate.settlement.SettlementCalculator.shares(after.get("expectedTotal").asLong(), weights, weights.size() - 1);
        assertThat(remaining.stream().map(r -> r.getRatioUnits()).toList()).isEqualTo(weights);
        assertThat(remaining.stream().map(r -> r.getExpectedShare()).toList()).isEqualTo(shares);
        assertThat(after.get("payerId").asLong()).isEqualTo(remaining.getLast().getMember().getId());
    }

    @Test
    void lastTwoCannotShrinkSoPartyIsCanceled() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToConfirmation();
        long first = onlyParty(users.get(0)).get("myRequestId").asLong();
        long second = onlyParty(users.get(1)).get("myRequestId").asLong();

        mvc.perform(delete("/api/requests/" + first).session(users.get(0))).andExpect(status().isOk());
        mvc.perform(delete("/api/requests/" + second).session(users.get(1))).andExpect(status().isOk());

        assertThat(state(users.get(2)).get("parties")).isEmpty();
        // 조기 확정 뒤 해산하면 모집 마감 전이므로 남은 요청은 다시 대기한다.
        assertThat(state(users.get(2)).get("latestRequest").get("status").asText()).isEqualTo("WAITING");
    }

    @Test
    void noShowIsRemovedAndArrivedRidersBoardWithNewPayer() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToConfirmation();
        long partyId = onlyParty(users.get(0)).get("id").asLong();
        legacyParty(partyId);
        mvc.perform(post("/api/parties/" + partyId + "/arrive").session(users.get(0))).andExpect(status().isOk());
        mvc.perform(post("/api/parties/" + partyId + "/arrive").session(users.get(1))).andExpect(status().isOk());

        advance(30);

        Member noShow = members.findByEmail("demo-f3@daejin.ac.kr").orElseThrow();
        assertThat(noShow.getNoShowCount()).isEqualTo(1);
        assertThat(state(users.get(2)).get("latestRequest").get("status").asText()).isEqualTo("CANCELED");
        assertThat(state(users.get(2)).get("parties")).isEmpty();
        JsonNode party = onlyParty(users.get(1));
        assertThat(party.get("members")).hasSize(2);
        assertThat(party.get("payer").asBoolean()).isTrue();
        assertThat(party.get("canSettle").asBoolean()).isTrue();
        assertThat(state(users.get(0)).get("latestRequest").get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void unsubmittedSettlementEndsAfterTwentyFourHours() throws Exception {
        List<MockHttpSession> users = departedParty();
        assertThat(state(users.get(0)).get("parties")).hasSize(1);

        advance(24 * 60 + 5);

        assertThat(state(users.get(0)).get("parties")).isEmpty();
        assertThat(state(users.get(0)).get("latestRequest").get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void amountCanBeCorrectedOnlyBeforeAnyoneSends() throws Exception {
        List<MockHttpSession> users = departedParty();
        MockHttpSession payer = payerOf(users);
        long id = onlyParty(payer).get("id").asLong();
        long total = onlyParty(payer).get("expectedTotal").asLong();
        submit(id, total, payer).andExpect(status().isOk());
        Instant previous = settlements.findAll().getFirst().getObjectionDeadlineAt();
        advance(60);
        correct(id, total + 1000, payer).andExpect(status().isOk());
        assertThat(settlements.findAll().getFirst().getObjectionDeadlineAt()).isAfter(previous);
        mvc.perform(post("/api/parties/" + id + "/settlement/objections").session(users.get(0))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"영수증 확인 요청\"}")).andExpect(status().isOk());
        correct(id, total, payer).andExpect(status().isConflict());
        assertThat(payouts.count()).isZero();
        assertThat(transfers.count()).isZero();
    }

    @Test
    void warningIsShownOverFifteenPercentButSettlementProceeds() throws Exception {
        List<MockHttpSession> users = departedParty();
        MockHttpSession payer = payerOf(users);
        JsonNode party = onlyParty(payer);
        long over = (long) (party.get("expectedTotal").asLong() * 1.2);

        submit(party.get("id").asLong(), over, payer).andExpect(status().isOk());

        assertThat(onlyParty(payer).get("settlement").get("warning").asBoolean()).isTrue();
    }

    @Test
    void smallAmountIsSplitWithoutNegativeShare() throws Exception {
        List<MockHttpSession> users = departedParty();
        MockHttpSession payer = payerOf(users);

        submit(onlyParty(payer).get("id").asLong(), 100, payer).andExpect(status().isOk());

        assertThat(onlyParty(payer).get("settlement").get("warning").asBoolean()).isTrue();
    }

    @Test
    void unsentTransferBlocksNewRequestAfterTwentyFourHoursUntilSent() throws Exception {
        List<MockHttpSession> users = departedParty();
        MockHttpSession payer = payerOf(users);
        long id = onlyParty(payer).get("id").asLong();
        submit(id, onlyParty(payer).get("expectedTotal").asLong() * 2, payer).andExpect(status().isOk());
        // 이 시나리오에서는 추가 결제를 자동 승인하지 않고 연체와 해제를 직접 검증한다.
        demo.advance(48 * 60);
        demo.advance(1);
        var extra = paymentOrders.findByPartyIdOrderById(id).stream()
                .filter(o -> o.getPurpose() == com.campustaximate.server.domain.PaymentPurpose.ADDITIONAL).findFirst().orElseThrow();
        MockHttpSession sender = sessionOf(users, extra.getMemberId());
        assertThat(state(sender).get("restricted").asBoolean()).isTrue();
        createRequest(sender, POINTS[0]).andExpect(status().isConflict());
        payments.confirm(extra.getMemberId(), extra.getOrderId(), "test_" + extra.getOrderId(), extra.getAmount());
        assertThat(state(sender).get("restricted").asBoolean()).isFalse();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body, MockHttpSession session) throws Exception {
        return mvc.perform(post(path).session(session).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void reminderGoesOnceTenMinutesBeforeBoarding() throws Exception {
        List<MockHttpSession> users = threeRequests();
        org.mockito.Mockito.clearInvocations(mail);
        advanceToConfirmation();
        long partyId = onlyParty(users.get(0)).get("id").asLong();
        Instant departure = parties.findById(partyId).orElseThrow().getDepartureAt();
        long minutes = java.time.Duration.between(clock.now(), departure).toMinutes() - 1;
        if (minutes > 0) {
            advance((int) minutes);
        }
        org.mockito.Mockito.verify(mail, org.mockito.Mockito.times(users.size()))
                .send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("[캠퍼스 택시메이트] 곧 승차 시각"),
                        org.mockito.ArgumentMatchers.anyString());
        advance(1);
        org.mockito.Mockito.verify(mail, org.mockito.Mockito.times(users.size()))
                .send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("[캠퍼스 택시메이트] 곧 승차 시각"),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void pushSubscriptionRejectsUnknownHostAndAccuracyIsReadable() throws Exception {
        MockHttpSession me = login("demo-f1");
        postJson("/api/push/subscription", "{\"endpoint\":\"https://evil.example/x\",\"keys\":{\"p256dh\":\"a\",\"auth\":\"b\"}}", me)
                .andExpect(status().isBadRequest());
        postJson("/api/push/subscription", "{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/x\",\"keys\":{\"p256dh\":\"a\",\"auth\":\"b\"}}", me)
                .andExpect(status().isOk());
        assertThat(read(mvc.perform(get("/api/push/key").session(me)).andReturn().getResponse().getContentAsString())
                .get("publicKey").asText()).isNotBlank();
        assertThat(read(mvc.perform(get("/api/stats/fare-accuracy").session(me)).andReturn().getResponse().getContentAsString())
                .has("settled")).isTrue();
    }

    @Test
    void newMemberLogsInWithMailCodeThenFillsProfile() throws Exception {
        String email = "new" + System.nanoTime() + "@daejin.ac.kr";
        MockHttpSession session = new MockHttpSession();

        postJson("/api/auth/code", "{\"email\":\"a@gmail.com\"}", session).andExpect(status().isBadRequest());
        String response = postJson("/api/auth/code", "{\"email\":\"" + email + "\"}", session)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("demoCode");

        String code = mailedCode(email);
        postJson("/api/auth/login", "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}", session)
                .andExpect(status().isOk());
        assertThat(read(mvc.perform(get("/api/me").session(session)).andReturn().getResponse().getContentAsString())
                .get("needsProfile").asBoolean()).isTrue();

        createRequest(session, POINTS[0]).andExpect(status().isBadRequest());
        postJson("/api/auth/profile", "{\"nickname\":\"새학생\",\"gender\":\"FEMALE\"}", session).andExpect(status().isOk());
        createRequest(session, POINTS[0]).andExpect(status().isOk());
        postJson("/api/auth/profile", "{\"nickname\":\"바꿈\",\"gender\":\"MALE\"}", session).andExpect(status().isConflict());
    }

    @Test
    void signupSetsPasswordThenSignsInWithPassword() throws Exception {
        String email = "pw" + System.nanoTime() + "@daejin.ac.kr";
        MockHttpSession first = new MockHttpSession();
        postJson("/api/auth/code", "{\"email\":\"" + email + "\"}", first).andExpect(status().isOk());
        postJson("/api/auth/login", "{\"email\":\"" + email + "\",\"code\":\"" + mailedCode(email) + "\"}", first)
                .andExpect(status().isOk());
        assertThat(read(mvc.perform(get("/api/me").session(first)).andReturn().getResponse().getContentAsString())
                .get("needsPassword").asBoolean()).isTrue();
        postJson("/api/auth/password", "{\"password\":\"short\"}", first).andExpect(status().isBadRequest());
        postJson("/api/auth/password", "{\"password\":\"taxi-mate-77\"}", first).andExpect(status().isOk());

        MockHttpSession second = new MockHttpSession();
        postJson("/api/auth/signin", "{\"email\":\"" + email + "\",\"password\":\"wrong-pass-1\"}", second)
                .andExpect(status().isBadRequest());
        postJson("/api/auth/signin", "{\"email\":\"" + email + "\",\"password\":\"taxi-mate-77\"}", second)
                .andExpect(status().isOk());
        assertThat(read(mvc.perform(get("/api/me").session(second)).andReturn().getResponse().getContentAsString())
                .get("needsPassword").asBoolean()).isFalse();
    }

    @Test
    void passwordCannotBeSetWithoutLogin() throws Exception {
        postJson("/api/auth/password", "{\"password\":\"taxi-mate-77\"}", new MockHttpSession())
                .andExpect(status().isUnauthorized());
    }

    /** 코드는 해시로만 저장되므로 보낸 메일에서 읽는다. */
    private String mailedCode(String email) {
        var text = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mail, org.mockito.Mockito.atLeastOnce())
                .sendNow(org.mockito.ArgumentMatchers.eq(email), org.mockito.ArgumentMatchers.anyString(), text.capture());
        return text.getValue().replaceAll("\\D", "").substring(0, 6);
    }

    private jakarta.servlet.http.Cookie cookieFromLogin(String id) throws Exception {
        String email = id + "@daejin.ac.kr";
        MockHttpSession session = new MockHttpSession();
        String code = read(postJson("/api/auth/code", "{\"email\":\"" + email + "\"}", session)
                .andReturn().getResponse().getContentAsString()).get("demoCode").asText();
        return postJson("/api/auth/login", "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}", session)
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("taximate_login");
    }

    @Test
    void sameDeviceStaysLoggedInWithoutSessionAndOnlyHashIsStored() throws Exception {
        jakarta.servlet.http.Cookie cookie = cookieFromLogin("demo-f1");

        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getMaxAge()).isEqualTo(365 * 24 * 3600);
        assertThat(loginTokens.findAll()).noneMatch(t -> t.getTokenHash().equals(cookie.getValue()));
        // 새 세션은 서버 재시작 뒤 접속과 같다.
        mvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    void logoutEndsOnlyThisDeviceAndLogoutAllEndsEveryDevice() throws Exception {
        jakarta.servlet.http.Cookie phone = cookieFromLogin("demo-f1");
        jakarta.servlet.http.Cookie laptop = cookieFromLogin("demo-f1");
        jakarta.servlet.http.Cookie tablet = cookieFromLogin("demo-f1");
        MockHttpSession laptopSession = new MockHttpSession();
        mvc.perform(get("/api/me").cookie(laptop).session(laptopSession)).andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout").cookie(phone).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me").cookie(phone)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").cookie(laptop).session(laptopSession)).andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout-all").cookie(tablet).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me").cookie(laptop).session(laptopSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void loginTokenExpiresAfterOneYear() throws Exception {
        jakarta.servlet.http.Cookie cookie = cookieFromLogin("demo-f1");

        clock.moveTo(clock.now().plusSeconds(365L * 24 * 3600 + 60));

        mvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void codeIsSingleUseAndResendIsLimited() throws Exception {
        String email = "once" + System.nanoTime() + "@daejin.ac.kr";
        MockHttpSession session = new MockHttpSession();
        postJson("/api/auth/code", "{\"email\":\"" + email + "\"}", session).andExpect(status().isOk());
        postJson("/api/auth/code", "{\"email\":\"" + email + "\"}", session).andExpect(status().isTooManyRequests());
        String code = mailedCode(email);
        String body = "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}";

        postJson("/api/auth/login", body, session).andExpect(status().isOk());
        postJson("/api/auth/login", body, new MockHttpSession()).andExpect(status().isBadRequest());
    }

    @Test
    void fiveWrongCodesInvalidateTheCode() throws Exception {
        String email = "guess" + System.nanoTime() + "@daejin.ac.kr";
        MockHttpSession session = new MockHttpSession();
        postJson("/api/auth/code", "{\"email\":\"" + email + "\"}", session).andExpect(status().isOk());
        String code = mailedCode(email);
        String wrong = code.equals("000000") ? "000001" : "000000";

        for (int i = 0; i < 5; i++) {
            postJson("/api/auth/login", "{\"email\":\"" + email + "\",\"code\":\"" + wrong + "\"}", session)
                    .andExpect(status().isBadRequest());
        }

        postJson("/api/auth/login", "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}", session)
                .andExpect(status().isBadRequest());
    }

    @Test
    void codeExpiresAfterTenMinutes() throws Exception {
        String email = "late" + System.nanoTime() + "@daejin.ac.kr";
        MockHttpSession session = new MockHttpSession();
        postJson("/api/auth/code", "{\"email\":\"" + email + "\"}", session).andExpect(status().isOk());
        String code = mailedCode(email);

        clock.moveTo(clock.now().plusSeconds(601));

        postJson("/api/auth/login", "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}", session)
                .andExpect(status().isBadRequest());
    }

    @Test
    void receiptFileIsDeletedAfterSevenDaysButSettlementRemains() throws Exception {
        List<MockHttpSession> users = departedParty();
        MockHttpSession payer = payerOf(users);
        JsonNode party = onlyParty(payer);
        long partyId = party.get("id").asLong();
        long total = party.get("expectedTotal").asLong();
        submit(partyId, total, payer).andExpect(status().isOk());
        mvc.perform(get("/api/parties/" + partyId + "/receipt").session(payer)).andExpect(status().isOk());
        String path = settlements.findAll().get(0).getReceiptPath();
        assertThat(java.nio.file.Path.of(path)).exists();

        clock.moveTo(clock.now().plusSeconds(7 * 24 * 3600L + 60));
        demo.runNow();

        assertThat(java.nio.file.Path.of(path)).doesNotExist();
        mvc.perform(get("/api/parties/" + partyId + "/receipt").session(payer)).andExpect(status().isNotFound());
        // 완료 일행은 활성 목록에서 빠지지만 완료 요약·직접 조회·이력에는 정산 기록이 남는다.
        var ledger=paymentOrders.findByPartyIdOrderById(partyId);
        assertThat(ledger).as("일행 전체 결제 장부: %s",ledger.stream().map(o->o.getId()+":"+o.getStatus()+":승인="+o.getApprovedAmount()+":환불="+o.getRefundedAmount()+"/"+o.getRefundTarget()+":작업="+paymentOperations.findByPaymentOrderIdOrderById(o.getId()).stream().map(op->op.getStatus()+":"+op.getLastError()+":"+op.getNextAttemptAt()+":"+op.getLeaseUntil()).toList()).toList())
                .hasSize(2).allMatch(o -> o.getApprovedAmount() > 0 && o.getRefundedAmount() == o.getRefundTarget());
        for(var order:ledger) {
            var work=paymentOperations.findByPaymentOrderIdOrderById(order.getId());
            assertThat(work).as("결제 작업: %s",work.stream().map(op->op.getId()+":"+op.getStatus()+":"+op.getLastError()+":"+op.getNextAttemptAt()+":"+op.getLeaseUntil()).toList())
                    .allMatch(op->op.getStatus()==com.campustaximate.server.domain.PaymentOperationStatus.SUCCEEDED);
        }
        JsonNode after = state(payer);
        assertThat(after.get("parties")).isEmpty();
        assertThat(after.get("settledParty").get("id").asLong()).isEqualTo(partyId);
        assertThat(after.get("settledParty").get("status").asText()).isEqualTo("COMPLETED");
        JsonNode completed = read(mvc.perform(get("/api/parties/" + partyId).session(payer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode settlement = completed.get("settlement");
        assertThat(settlement.get("receiptAvailable").asBoolean()).isFalse();
        assertThat(settlement.get("paidAmount").asLong()).isEqualTo(total);
        assertThat(settlements.findAll()).hasSize(1);
        JsonNode entry = history(payer);
        assertThat(entry.get("partyId").asLong()).isEqualTo(partyId);
        assertThat(entry.get("stage").asText()).isEqualTo("SETTLED");
    }

    @Test
    void correctionReplacesReceiptFile() throws Exception {
        List<MockHttpSession> users = departedParty();
        MockHttpSession payer = payerOf(users);
        JsonNode party = onlyParty(payer);
        long partyId = party.get("id").asLong();
        submit(partyId, party.get("expectedTotal").asLong(), payer).andExpect(status().isOk());
        String oldPath = settlements.findAll().get(0).getReceiptPath();

        correct(partyId, party.get("expectedTotal").asLong() + 500, payer).andExpect(status().isOk());

        String newPath = settlements.findAll().get(0).getReceiptPath();
        assertThat(newPath).isNotEqualTo(oldPath);
        assertThat(java.nio.file.Path.of(oldPath)).doesNotExist();
        assertThat(java.nio.file.Path.of(newPath)).exists();
    }

    @Test
    void rejectsSvgReceiptAndHugeAmount() throws Exception {
        List<MockHttpSession> users = departedParty();
        MockHttpSession payer = payerOf(users);
        long partyId = onlyParty(payer).get("id").asLong();
        MockMultipartFile svg = new MockMultipartFile("receipt", "r.svg", "image/svg+xml", "<svg/>".getBytes());

        mvc.perform(multipart("/api/parties/" + partyId + "/settlement").file(svg)
                .param("amount", "20000").param("bankName", "OO은행").param("accountNumber", "000-0000-0000").session(payer)).andExpect(status().isBadRequest());
        submit(partyId, 1_000_000_000_000_000L, payer).andExpect(status().isBadRequest());
    }

    @Test
    void cannotMarkArrivedAfterDeparture() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToConfirmation();
        JsonNode party = onlyParty(users.get(0));
        clock.moveTo(Instant.parse(party.get("departureAt").asText()).plusSeconds(1));

        mvc.perform(post("/api/parties/" + party.get("id").asLong() + "/arrive").session(users.get(0)))
                .andExpect(status().isConflict());
    }

    @Test
    void outsiderCannotSeeOrChangeAParty() throws Exception {
        List<MockHttpSession> users = departedParty();
        MockHttpSession payer = payerOf(users);
        JsonNode party = onlyParty(payer);
        long partyId = party.get("id").asLong();
        submit(partyId, party.get("expectedTotal").asLong(), payer).andExpect(status().isOk());
        var paymentOrder = paymentOrders.findByPartyIdOrderById(partyId).getFirst();
        MockHttpSession outsider = login("demo-m1");

        mvc.perform(get("/api/parties/" + partyId + "/routes").session(outsider)).andExpect(status().isForbidden());
        mvc.perform(get("/api/parties/" + partyId + "/routes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/parties/" + partyId).session(outsider)).andExpect(status().isForbidden());
        mvc.perform(get("/api/parties/" + partyId + "/receipt").session(outsider)).andExpect(status().isForbidden());
        submit(partyId, 1000, outsider).andExpect(status().isForbidden());
        mvc.perform(get("/api/payments/orders/" + paymentOrder.getOrderId()).session(outsider)).andExpect(status().isForbidden());
        mvc.perform(get("/api/state")).andExpect(status().isUnauthorized());
    }

    @Test
    void demoActMarksOnlySampleMembers() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToConfirmation();
        MockHttpSession me = users.get(0);
        long partyId = onlyParty(me).get("id").asLong();

        mvc.perform(post("/api/demo/act").session(me)).andExpect(status().isOk());

        JsonNode party = onlyParty(me);
        assertThat(party.get("canArrive").asBoolean()).isTrue();
        long arrived = 0;
        for (JsonNode m : party.get("members")) {
            arrived += m.get("arrived").asBoolean() ? 1 : 0;
        }
        assertThat(arrived).isEqualTo(2);
        mvc.perform(post("/api/parties/" + partyId + "/arrive").session(me)).andExpect(status().isOk());
        advance(30);
        assertThat(state(me).get("parties")).hasSize(1);
    }

    @Test
    void validatesRequestWindow() throws Exception {
        MockHttpSession user = login("demo-f1");
        Instant start = AppClock.nextSlotAfter(clock.now()).plusSeconds(3600);

        postRequest(user, start.plusSeconds(60), start.plusSeconds(1800), POINTS[0]).andExpect(status().isBadRequest());
        postRequest(user, start, start.plusSeconds(3900), POINTS[0]).andExpect(status().isBadRequest());
        Instant soon = AppClock.nextSlotAfter(clock.now());
        postRequest(user, soon, soon.plusSeconds(600), POINTS[0]).andExpect(status().isBadRequest());
    }

    @Test
    void oneActiveRequestPerMemberUnderConcurrency() throws Exception {
        MockHttpSession user = login("demo-f1");
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> createRequest(user, POINTS[0]).andReturn().getResponse().getStatus());
        }
        int ok = 0;
        for (Future<Integer> result : pool.invokeAll(tasks)) {
            if (result.get() == 200) {
                ok++;
            }
        }
        pool.shutdown();

        assertThat(ok).isEqualTo(1);
    }

    @Test
    void concurrentCancelAndMatchingNeverDoubleAssign() throws Exception {
        List<MockHttpSession> users = threeRequests();
        long firstRequest = state(users.get(0)).get("latestRequest").get("id").asLong();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Callable<Integer>> tasks = new ArrayList<>();
        clock.moveTo(AppClock.nextSlotAfter(clock.now()).plusSeconds(15 * 60L + 1));
        for (int i = 0; i < 9; i++) {
            tasks.add(() -> {
                demo.runNow();
                return 0;
            });
        }
        tasks.add(() -> mvc.perform(delete("/api/requests/" + firstRequest).session(users.get(0)))
                .andReturn().getResponse().getStatus());
        for (Future<Integer> result : pool.invokeAll(tasks)) {
            result.get();
        }
        pool.shutdown();

        for (MockHttpSession user : users) {
            assertThat(state(user).get("parties").size()).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void repeatedDepartureCheckCountsNoShowOnce() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToConfirmation();
        long partyId = onlyParty(users.get(0)).get("id").asLong();
        legacyParty(partyId);
        mvc.perform(post("/api/parties/" + partyId + "/arrive").session(users.get(0))).andExpect(status().isOk());
        mvc.perform(post("/api/parties/" + partyId + "/arrive").session(users.get(1))).andExpect(status().isOk());
        org.mockito.Mockito.doThrow(new org.springframework.dao.OptimisticLockingFailureException("x"))
                .when(routes).route(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        advance(40);
        org.mockito.Mockito.reset(routes);
        advance(5);

        assertThat(members.findByEmail("demo-f3@daejin.ac.kr").orElseThrow().getNoShowCount()).isEqualTo(1);
    }

    @Test
    void waitingScreenExplainsWhyNoPartyYet() throws Exception {
        MockHttpSession me = login("demo-f1");
        createRequest(me, POINTS[0]).andExpect(status().isOk());
        assertThat(state(me).get("latestRequest").get("insight").get("stage").asText()).isEqualTo("ALONE");

        createRequest(login("demo-f2"), new double[] {37.95, 127.25}).andExpect(status().isOk());
        JsonNode other = state(me).get("latestRequest").get("insight");
        assertThat(other.get("stage").asText()).isEqualTo("OTHER_DIRECTION");
        assertThat(other.get("peers").asInt()).isEqualTo(1);

        createRequest(login("demo-f3"), POINTS[1]).andExpect(status().isOk());
        JsonNode candidates = state(me).get("latestRequest").get("insight");
        assertThat(candidates.get("stage").asText()).isEqualTo("CANDIDATES");
        assertThat(candidates.get("sameDirection").asInt()).isEqualTo(1);

        MockHttpSession early = login("demo-f4");
        Instant later = AppClock.nextSlotAfter(clock.now()).plusSeconds(7200);
        postRequest(early, later, later.plusSeconds(3600), POINTS[2]).andExpect(status().isOk());
        assertThat(state(early).get("latestRequest").get("insight").get("stage").asText()).isEqualTo("ALONE");
    }

    @Test
    void historyShowsSettlementStageAndWhatIPaid() throws Exception {
        List<MockHttpSession> users = departedParty();
        long id = onlyParty(users.get(0)).get("id").asLong();
        assertThat(history(users.get(0)).get("stage").asText()).isEqualTo("AWAITING_AMOUNT");
        submit(id, 15000, payerOf(users)).andExpect(status().isOk());
        JsonNode mine = history(users.get(0));
        assertThat(mine.get("stage").asText()).isEqualTo("OBJECTION_WINDOW");
        assertThat(mine.get("paid").asLong()).isPositive();
        assertThat(mine.get("partyPaid").asLong()).isEqualTo(15000);
        assertThat(mine.get("partySize").asInt()).isEqualTo(3);
        advance(24 * 60 + 1);
        assertThat(allocations.findAll().stream().mapToLong(a -> a.getAmount()).sum()).isEqualTo(15000);
        assertThat(payouts.findAll()).hasSize(1);
        assertThat(transfers.count()).isZero();
    }

    @Test
    void demoMatchPutsSamplesOnMyRouteAndBuildsParty() throws Exception {
        MockHttpSession me = login("demo-f1");
        mvc.perform(post("/api/demo/match").session(me)).andExpect(status().isConflict());
        createRequest(me, POINTS[2]).andExpect(status().isOk());

        JsonNode outcome = read(mvc.perform(post("/api/demo/match").session(me)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(outcome.get("status").asText()).isEqualTo("MATCHED");
        assertThat(onlyParty(me).get("members")).hasSize(3);
    }

    /** 자동 노쇼·승계는 레거시 회귀로 계속 검증한다. 새 흐름은 운행 확인 테스트가 맡는다. */
    private void legacyParty(long id) {
        var party=parties.findById(id).orElseThrow(); party.setPaymentFlowVersion(0);
        party.setPrepayDeadlineAt(null); party.setConfirmedAt(null);
        jdbc.update("delete from payment_operation where payment_order_id in (select id from payment_order where party_id=?)",id);
        jdbc.update("delete from payment_order where party_id=?",id);
        party.setStatus(com.campustaximate.server.domain.PartyStatus.CONFIRMED); parties.save(party);
    }

    private JsonNode history(MockHttpSession user) throws Exception {
        return read(mvc.perform(get("/api/history").session(user)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get(0);
    }

    private List<MockHttpSession> departedParty() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToConfirmation();
        long partyId = onlyParty(users.get(0)).get("id").asLong();
        for (MockHttpSession user : users) {
            mvc.perform(post("/api/parties/" + partyId + "/arrive").session(user)).andExpect(status().isOk());
        }
        advance(30);
        return users;
    }

    private List<MockHttpSession> threeRequests() throws Exception {
        List<MockHttpSession> users = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            MockHttpSession user = login("demo-f" + (i + 1));
            createRequest(user, POINTS[i]).andExpect(status().isOk());
            users.add(user);
        }
        return users;
    }

    private org.springframework.test.web.servlet.ResultActions createRequest(MockHttpSession user, double[] point) throws Exception {
        Instant start = AppClock.nextSlotAfter(clock.now()).plusSeconds(1800);
        return postRequest(user, start, start.plusSeconds(3600), point);
    }

    private org.springframework.test.web.servlet.ResultActions postRequest(MockHttpSession user, Instant start, Instant end, double[] point) throws Exception {
        String body = json.writeValueAsString(Map.of("hubId", hubId, "windowStart", start.toString(),
                "windowEnd", end.toString(), "direction", "FROM_HUB", "pointName", "샘플", "pointLat", point[0], "pointLon", point[1]));
        return mvc.perform(post("/api/requests").session(user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private org.springframework.test.web.servlet.ResultActions submit(long partyId, long amount, MockHttpSession user) throws Exception {
        MockMultipartFile receipt = new MockMultipartFile("receipt", "r.png", "image/png", new byte[] {(byte) 0x89, 80, 78, 71, 13, 10, 26, 10, 1});
        return mvc.perform(multipart("/api/parties/" + partyId + "/settlement").file(receipt)
                .param("amount", String.valueOf(amount)).param("bankName", "OO은행").param("accountNumber", "000-0000-0000").session(user));
    }

    private org.springframework.test.web.servlet.ResultActions correct(long partyId, long amount, MockHttpSession user) throws Exception {
        MockMultipartFile receipt = new MockMultipartFile("receipt", "n.png", "image/png", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 4});
        return mvc.perform(multipart("/api/parties/" + partyId + "/settlement/correction").file(receipt)
                .param("amount", String.valueOf(amount)).param("bankName", "OO은행").param("accountNumber", "000-0000-0000").session(user));
    }

    private org.springframework.test.web.servlet.ResultActions sent(JsonNode transfer, int revision, MockHttpSession user) throws Exception {
        return mvc.perform(post("/api/transfers/" + transfer.get("id").asLong() + "/sent").session(user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":" + revision + "}"));
    }

    private MockHttpSession login(String id) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String email = id + "@daejin.ac.kr";
        String response = postJson("/api/auth/code", "{\"email\":\"" + email + "\"}", session)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String code = read(response).get("demoCode").asText();
        postJson("/api/auth/login", "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}", session)
                .andExpect(status().isOk());
        return session;
    }

    private JsonNode state(MockHttpSession user) throws Exception {
        return read(mvc.perform(get("/api/state").session(user)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode onlyParty(MockHttpSession user) throws Exception {
        JsonNode list = state(user).get("parties");
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    private MockHttpSession payerOf(List<MockHttpSession> users) throws Exception {
        for (MockHttpSession user : users) {
            if (onlyParty(user).get("payer").asBoolean()) {
                return user;
            }
        }
        throw new AssertionError("no payer");
    }

    private MockHttpSession sessionOf(List<MockHttpSession> users, long memberId) throws Exception {
        for (MockHttpSession user : users) {
            JsonNode me = read(mvc.perform(get("/api/me").session(user)).andReturn().getResponse().getContentAsString());
            if (me.get("id").asLong() == memberId) {
                return user;
            }
        }
        throw new AssertionError("no session");
    }

    private JsonNode read(String body) throws Exception {
        return json.readTree(body);
    }

    /** 예약 2~3명은 최초 요청 10분 뒤 첫 예약 회차에 확정한다. */
    private void advanceToConfirmation() {
        for (int minute = 0; minute < 20; minute++) {
            if (!requests.findByStatus(com.campustaximate.server.domain.RequestStatus.MATCHED).isEmpty()) return;
            advance(1);
        }
        assertThat(requests.findByStatus(com.campustaximate.server.domain.RequestStatus.MATCHED)).isNotEmpty();
    }
    private void advance(int minutes) {
        PaymentTestSupport.approveEligible(payments, paymentOrders, parties, clock);
        demo.advance(minutes);
        PaymentTestSupport.approveEligible(payments, paymentOrders, parties, clock);
        payments.retryDue();
    }
}
