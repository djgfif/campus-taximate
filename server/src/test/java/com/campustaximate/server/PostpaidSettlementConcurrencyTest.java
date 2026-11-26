package com.campustaximate.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.PartyStatus;
import com.campustaximate.server.domain.PaymentOrder;
import com.campustaximate.server.domain.PaymentPurpose;
import com.campustaximate.server.domain.PaymentStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.domain.Settlement;
import com.campustaximate.server.payment.PaymentGateway;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.repository.PartyRepository;
import com.campustaximate.server.repository.PaymentOrderRepository;
import com.campustaximate.server.repository.PayoutLedgerRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.repository.SettlementConfirmationRepository;
import com.campustaximate.server.repository.SettlementRepository;
import com.campustaximate.server.service.AdminService;
import com.campustaximate.server.service.BoardingCodeService;
import com.campustaximate.server.service.DemoService;
import com.campustaximate.server.service.LoginTokenService;
import com.campustaximate.server.service.PartyReadinessService;
import com.campustaximate.server.service.PartyService;
import com.campustaximate.server.service.PaymentLifecycleService;
import com.campustaximate.server.service.PaymentMethodService;
import com.campustaximate.server.service.PaymentService;
import com.campustaximate.server.service.SettlementService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:postpaid-conc;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.kakao.rest-key=", "app.tmap.app-key=", "app.toss.secret-key=", "app.mail.enabled=false",
        "app.fare.quote-bias=0", "app.fare.quote-margin=0.50", "app.upload-dir=target/postpaid-conc-uploads"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@TestPropertySource(properties = "app.payment.flow-version=3")
@Import(PeopleBoardFlowTest.FixedTime.class)
class PostpaidSettlementConcurrencyTest {
    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    @Autowired private AppClock clock;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DemoService demo;
    @Autowired private LoginTokenService loginTokens;
    @Autowired private PartyReadinessService readiness;
    @Autowired private BoardingCodeService boarding;
    @Autowired private PartyService partyService;
    @Autowired private PaymentService payments;
    @Autowired private PaymentMethodService paymentMethods;
    @Autowired private PaymentLifecycleService lifecycle;
    @Autowired private SettlementService settlements;
    @Autowired private AdminService adminService;
    @Autowired private MemberRepository members;
    @Autowired private PartyRepository parties;
    @Autowired private RideRequestRepository requests;
    @Autowired private PaymentOrderRepository orders;
    @Autowired private SettlementRepository settlementRecords;
    @Autowired private SettlementConfirmationRepository confirmations;
    @Autowired private PayoutLedgerRepository payouts;
    @SpyBean private PaymentGateway gateway;

    private long joiner;
    private long adminId;

    @BeforeEach
    void resetState() {
        reset(gateway);
        PaymentTestSupport.clear(jdbc);
        jdbc.update("delete from party_chat_read");
        jdbc.update("delete from party_message");
        jdbc.update("delete from ride_review");
        settlementRecords.deleteAll();
        requests.deleteAll();
        parties.deleteAll();
        clock.moveTo(NOW);
        joiner = demoMember(4);
        paymentMethods.seedDemo(joiner);
        adminId = members.findByEmail("demo-admin@daejin.ac.kr").orElseThrow().getId();
    }

    @AfterEach
    void tearDownGateway() {
        reset(gateway);
    }

    @Test
    void allRidersConfirmAndPayCompletesImmediatelyAndPopulatesSettlementView() throws Exception {
        Party party = startThreeRiderPartyAndSubmit(1.1);
        long payerId = party.getPayer().getId();
        List<Long> riders = nonPayerIds(party);
        long rider1 = riders.get(0);
        long rider2 = riders.get(1);
        Settlement settlement = settlementRecords.findByParty(party).orElseThrow();

        var initialPayerView = partyService.view(payerId, party.getId()).settlement();
        assertThat(initialPayerView.canCorrect()).isTrue();
        assertThat(initialPayerView.confirmedCount()).isZero();
        assertThat(initialPayerView.totalRidersToConfirm()).isEqualTo(2);
        assertThat(initialPayerView.payable()).isTrue();
        assertThat(initialPayerView.autoFinalizeAt()).isEqualTo(settlement.getObjectionDeadlineAt());
        assertThat(initialPayerView.collectedPayoutAmount()).isZero();
        assertThat(initialPayerView.unpaidAmount()).isEqualTo(initialPayerView.expectedPayoutAmount());

        PaymentOrder order1 = postpaidOrder(party.getId(), rider1);
        mvc.perform(post("/api/parties/{id}/settlement/confirm-and-pay", party.getId())
                        .session(session(rider1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(0, order1.getOrderId(), order1.getAmount(), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processing").value(false))
                .andExpect(jsonPath("$.settled").value(false))
                .andExpect(jsonPath("$.partyStatus").value("OBJECTION_WINDOW"))
                .andExpect(jsonPath("$.order.status").value("APPROVED"));

        var midPayerView = partyService.view(payerId, party.getId()).settlement();
        assertThat(midPayerView.canCorrect()).isFalse();
        assertThat(midPayerView.confirmedCount()).isEqualTo(1);
        assertThat(midPayerView.collectedPayoutAmount()).isEqualTo(order1.getAmount());
        assertThat(partyService.view(rider1, party.getId()).settlement().myConfirmed()).isTrue();
        assertThat(partyService.view(rider2, party.getId()).settlement().myConfirmed()).isFalse();

        PaymentOrder order2 = postpaidOrder(party.getId(), rider2);
        mvc.perform(post("/api/parties/{id}/settlement/confirm-and-pay", party.getId())
                        .session(session(rider2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(0, order2.getOrderId(), order2.getAmount(), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processing").value(false))
                .andExpect(jsonPath("$.settled").value(true))
                .andExpect(jsonPath("$.partyStatus").value("COMPLETED"));

        Party completed = parties.findById(party.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(PartyStatus.COMPLETED);
        var ledger = payouts.findBySettlementId(settlement.getId()).orElseThrow();
        assertThat(ledger.getExpectedAmount()).isEqualTo(order1.getAmount() + order2.getAmount());
        assertThat(ledger.getCollectedAmount()).isEqualTo(ledger.getExpectedAmount());
        assertThat(ledger.getAmount()).isEqualTo(ledger.getExpectedAmount());
    }

    @Test
    void legacyConfirmEndpointRejectsPostpaidOrders() throws Exception {
        Party party = startTwoRiderPartyAndSubmit(1.0);
        long passengerId = nonPayerIds(party).getFirst();
        PaymentOrder order = postpaidOrder(party.getId(), passengerId);

        mvc.perform(post("/api/payments/confirm")
                        .session(session(passengerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"orderId\":\"%s\",\"paymentKey\":\"pk_1\",\"amount\":%d}",
                                order.getOrderId(), order.getAmount())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USE_CONFIRM_AND_PAY"));
    }

    @Test
    void staleRevisionReturnsSettlementRevisedThenLatestRevisionSucceeds() throws Exception {
        Party party = startTwoRiderPartyAndSubmit(1.0);
        long payerId = party.getPayer().getId();
        long passengerId = nonPayerIds(party).getFirst();
        PaymentOrder initialOrder = postpaidOrder(party.getId(), passengerId);
        long newFare = party.getExpectedTotal() + 2_000;

        settlements.correct(payerId, party.getId(), newFare, receipt(), null, null);

        mvc.perform(post("/api/parties/{id}/settlement/confirm-and-pay", party.getId())
                        .session(session(passengerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(0, initialOrder.getOrderId(), initialOrder.getAmount(), null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_REVISED"));

        PaymentOrder latestOrder = postpaidOrder(party.getId(), passengerId);
        mvc.perform(post("/api/parties/{id}/settlement/confirm-and-pay", party.getId())
                        .session(session(passengerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(1, latestOrder.getOrderId(), latestOrder.getAmount(), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settled").value(true))
                .andExpect(jsonPath("$.partyStatus").value("COMPLETED"));
    }

    @Test
    void concurrentPayerCorrectionAndConfirmAndPayMaintainMutualExclusion() throws Exception {
        Party party = startTwoRiderPartyAndSubmit(1.0);
        long payerId = party.getPayer().getId();
        long passengerId = nonPayerIds(party).getFirst();
        PaymentOrder order = postpaidOrder(party.getId(), passengerId);
        long updatedFare = party.getExpectedTotal() + 1_500;

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> payerFuture = pool.submit((Callable<String>) () -> {
                startGate.await();
                try {
                    settlements.correct(payerId, party.getId(), updatedFare, receipt(), null, null);
                    return "OK";
                } catch (ApiException e) {
                    return e.getCode();
                }
            });
            Future<String> riderFuture = pool.submit((Callable<String>) () -> {
                startGate.await();
                try {
                    settlements.confirmAndPay(passengerId, party.getId(), 0,
                            order.getOrderId(), order.getAmount(), null, null);
                    return "OK";
                } catch (ApiException e) {
                    return e.getCode();
                }
            });
            startGate.countDown();
            String payerOutcome = payerFuture.get();
            String riderOutcome = riderFuture.get();

            assertThat(List.of(payerOutcome, riderOutcome))
                    .isIn(List.of("OK", "SETTLEMENT_REVISED"), List.of("ALREADY_PAID", "OK"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pgTimeoutReturns202BlocksCorrectionAndConvergesOnRetryDueWithoutWaitingSixHours() throws Exception {
        Party party = startTwoRiderPartyAndSubmit(1.0);
        long payerId = party.getPayer().getId();
        long passengerId = nonPayerIds(party).getFirst();
        PaymentOrder order = postpaidOrder(party.getId(), passengerId);

        doThrow(new PaymentGateway.Unavailable("PG 응답 지연"))
                .when(gateway).confirm(anyString(), eq(order.getOrderId()), anyLong(), anyString());
        doThrow(new PaymentGateway.Unavailable("조회 지연"))
                .when(gateway).get(anyString());

        mvc.perform(post("/api/parties/{id}/settlement/confirm-and-pay", party.getId())
                        .session(session(passengerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(0, order.getOrderId(), order.getAmount(), null)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.processing").value(true))
                .andExpect(jsonPath("$.settled").value(false))
                .andExpect(jsonPath("$.order.status").value("PROCESSING"));

        assertThat(partyService.view(passengerId, party.getId()).settlement().myConfirmed()).isTrue();
        assertThatThrownBy(() -> settlements.correct(payerId, party.getId(), party.getExpectedTotal() + 1_000, receipt(), null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("ALREADY_PAID");
                });

        reset(gateway);
        clock.moveTo(clock.now().plusSeconds(60));
        lifecycle.afterDepartures();

        Party settled = parties.findById(party.getId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(PartyStatus.COMPLETED);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        var ledger = payouts.findByPartyId(party.getId()).orElseThrow();
        assertThat(ledger.getCollectedAmount()).isEqualTo(ledger.getExpectedAmount()).isEqualTo(order.getAmount());
    }

    @Test
    void pgRejectedClearsConfirmationResetsOrderAndAllowsRetryWithNewKey() throws Exception {
        Party party = startTwoRiderPartyAndSubmit(1.0);
        long payerId = party.getPayer().getId();
        long passengerId = nonPayerIds(party).getFirst();
        PaymentOrder order = postpaidOrder(party.getId(), passengerId);
        Settlement settlement = settlementRecords.findByParty(party).orElseThrow();

        doThrow(new PaymentGateway.Rejected("REJECT_CARD_PAYMENT", null))
                .when(gateway).confirm(eq("declined_key"), eq(order.getOrderId()), anyLong(), anyString());

        mvc.perform(post("/api/parties/{id}/settlement/confirm-and-pay", party.getId())
                        .session(session(passengerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(0, order.getOrderId(), order.getAmount(), "declined_key")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_REJECTED"));

        PaymentOrder resetOrder = orders.findById(order.getId()).orElseThrow();
        assertThat(resetOrder.getStatus()).isEqualTo(PaymentStatus.REQUIRED);
        assertThat(resetOrder.getPaymentKey()).isNull();
        assertThat(confirmations.existsBySettlementIdAndMemberIdAndRevision(settlement.getId(), passengerId, 0)).isFalse();
        assertThat(partyService.view(payerId, party.getId()).settlement().canCorrect()).isTrue();

        mvc.perform(post("/api/parties/{id}/settlement/confirm-and-pay", party.getId())
                        .session(session(passengerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(0, order.getOrderId(), order.getAmount(), "valid_key_2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settled").value(true))
                .andExpect(jsonPath("$.partyStatus").value("COMPLETED"));
    }

    @Test
    void paidRiderCannotObjectAndAdminDecreaseRefundsPaidRiderWhileIncreaseCreatesAdditionalOrder() throws Exception {
        // Case A: Admin fare decrease triggers partial refund for already-paid rider
        Party decreaseParty = startThreeRiderPartyAndSubmit(1.1);
        List<Long> ridersA = nonPayerIds(decreaseParty);
        long paidRiderA = ridersA.get(0);
        long unpaidRiderA = ridersA.get(1);
        PaymentOrder paidOrderA = postpaidOrder(decreaseParty.getId(), paidRiderA);
        PaymentOrder unpaidOrderA = postpaidOrder(decreaseParty.getId(), unpaidRiderA);

        settlements.confirmAndPay(paidRiderA, decreaseParty.getId(), 0,
                paidOrderA.getOrderId(), paidOrderA.getAmount(), null, null);

        mvc.perform(post("/api/parties/{id}/settlement/objections", decreaseParty.getId())
                        .session(session(paidRiderA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"뒤늦은 이의\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CONFIRMED"));

        settlements.objectToFare(unpaidRiderA, decreaseParty.getId(), "미터기 금액 재확인 요청");
        assertThat(partyService.view(unpaidRiderA, decreaseParty.getId()).settlement().payable()).isFalse();
        mvc.perform(post("/api/parties/{id}/settlement/confirm-and-pay", decreaseParty.getId())
                        .session(session(unpaidRiderA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(0, unpaidOrderA.getOrderId(), unpaidOrderA.getAmount(), null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_LOCKED"));

        long lowerTotal = settlementRecords.findByParty(decreaseParty).orElseThrow().getPaidAmount() - 3_000;
        adminService.decideFare(adminId, decreaseParty.getId(), true, lowerTotal, "영수증 재검토 감액");
        lifecycle.afterDepartures();

        PaymentOrder refundedOrderA = orders.findById(paidOrderA.getId()).orElseThrow();
        PaymentOrder revisedOrderB = postpaidOrder(decreaseParty.getId(), unpaidRiderA);
        long adjustedShareA = partyService.view(paidRiderA, decreaseParty.getId()).settlement().shares()
                .stream().filter(s -> s.memberId() == paidRiderA).findFirst().orElseThrow().amount();
        assertThat(refundedOrderA.getRefundTarget()).isEqualTo(paidOrderA.getAmount() - adjustedShareA);
        assertThat(refundedOrderA.getRefundedAmount()).isEqualTo(refundedOrderA.getRefundTarget());
        assertThat(revisedOrderB.getOrderId()).isNotEqualTo(unpaidOrderA.getOrderId());
        assertThat(revisedOrderB.getSettlementRevision()).isEqualTo(1);
        assertThat(revisedOrderB.getAmount()).isLessThan(unpaidOrderA.getAmount());

        settlements.confirmAndPay(unpaidRiderA, decreaseParty.getId(), 1,
                revisedOrderB.getOrderId(), revisedOrderB.getAmount(), null, null);
        assertThat(parties.findById(decreaseParty.getId()).orElseThrow().getStatus()).isEqualTo(PartyStatus.COMPLETED);
        var ledgerA = payouts.findByPartyId(decreaseParty.getId()).orElseThrow();
        assertThat(ledgerA.getCollectedAmount()).isEqualTo(ledgerA.getExpectedAmount());

        // Reset clean slate for Case B: Admin fare increase creates delta ADDITIONAL order for paid rider
        resetState();
        Party increaseParty = startThreeRiderPartyAndSubmit(1.0);
        List<Long> ridersB = nonPayerIds(increaseParty);
        long paidRiderB = ridersB.get(0);
        long unpaidRiderB = ridersB.get(1);
        PaymentOrder paidOrderB = postpaidOrder(increaseParty.getId(), paidRiderB);
        PaymentOrder unpaidOrderB = postpaidOrder(increaseParty.getId(), unpaidRiderB);

        settlements.confirmAndPay(paidRiderB, increaseParty.getId(), 0,
                paidOrderB.getOrderId(), paidOrderB.getAmount(), null, null);
        settlements.objectToFare(unpaidRiderB, increaseParty.getId(), "추가 통행료 누락");

        long higherTotal = settlementRecords.findByParty(increaseParty).orElseThrow().getPaidAmount() + 3_000;
        adminService.decideFare(adminId, increaseParty.getId(), true, higherTotal, "하이패스 통행료 합산");
        lifecycle.afterDepartures();

        PaymentOrder deltaOrder = orders
                .findByPartyIdAndMemberIdAndPurpose(increaseParty.getId(), paidRiderB, PaymentPurpose.ADDITIONAL)
                .orElseThrow();
        PaymentOrder raisedOrder = postpaidOrder(increaseParty.getId(), unpaidRiderB);
        long adjustedShareForPaidRider = partyService.view(paidRiderB, increaseParty.getId()).settlement().shares()
                .stream().filter(s -> s.memberId() == paidRiderB).findFirst().orElseThrow().amount();
        assertThat(deltaOrder.getAmount()).isPositive();
        assertThat(paidOrderB.getAmount() + deltaOrder.getAmount()).isEqualTo(adjustedShareForPaidRider);
        assertThat(raisedOrder.getOrderId()).isNotEqualTo(unpaidOrderB.getOrderId());

        settlements.confirmAndPay(unpaidRiderB, increaseParty.getId(), 1,
                raisedOrder.getOrderId(), raisedOrder.getAmount(), null, null);
        assertThat(parties.findById(increaseParty.getId()).orElseThrow().getStatus()).isEqualTo(PartyStatus.SETTLED);

        payments.confirm(paidRiderB, deltaOrder.getOrderId(), "delta_" + deltaOrder.getOrderId(), deltaOrder.getAmount());
        assertThat(parties.findById(increaseParty.getId()).orElseThrow().getStatus()).isEqualTo(PartyStatus.COMPLETED);
        var ledgerB = payouts.findByPartyId(increaseParty.getId()).orElseThrow();
        assertThat(ledgerB.getCollectedAmount()).isEqualTo(ledgerB.getExpectedAmount())
                .isEqualTo(paidOrderB.getAmount() + deltaOrder.getAmount() + raisedOrder.getAmount());
    }

    @Test
    void adminAdjustmentRejectsWithoutOverwritingWhenPostpaidOrderIsAdminRequired() throws Exception {
        Party party = startThreeRiderPartyAndSubmit(1.0);
        List<Long> riders = nonPayerIds(party);
        long timedOutRider = riders.get(0);
        long objectingRider = riders.get(1);
        PaymentOrder targetOrder = postpaidOrder(party.getId(), timedOutRider);

        doThrow(new PaymentGateway.Unavailable("PG 1~4회 연속 응답 지연"))
                .when(gateway).confirm(anyString(), eq(targetOrder.getOrderId()), anyLong(), anyString());
        doThrow(new PaymentGateway.Unavailable("PG 조회 지연"))
                .when(gateway).get(anyString());

        mvc.perform(post("/api/parties/{id}/settlement/confirm-and-pay", party.getId())
                        .session(session(timedOutRider))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(0, targetOrder.getOrderId(), targetOrder.getAmount(), null)))
                .andExpect(status().isAccepted());

        for (int attempt = 2; attempt <= 4; attempt++) {
            clock.moveTo(clock.now().plusSeconds(61));
            payments.retryDue();
        }
        PaymentOrder adminRequired = orders.findById(targetOrder.getId()).orElseThrow();
        assertThat(adminRequired.getStatus()).isEqualTo(PaymentStatus.ADMIN_REQUIRED);
        String originalKey = adminRequired.getPaymentKey();
        assertThat(originalKey).isNotBlank();

        settlements.objectToFare(objectingRider, party.getId(), "요금 확인 요청");
        long adjustedFare = settlementRecords.findByParty(party).orElseThrow().getPaidAmount() + 2_000;
        assertThatThrownBy(() -> adminService.decideFare(adminId, party.getId(), true, adjustedFare, "증액 시도"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("PAYMENT_ADMIN_REQUIRED");
                });

        PaymentOrder unchanged = orders.findById(targetOrder.getId()).orElseThrow();
        assertThat(unchanged.getOrderId()).isEqualTo(targetOrder.getOrderId());
        assertThat(unchanged.getPaymentKey()).isEqualTo(originalKey);
        assertThat(unchanged.getStatus()).isEqualTo(PaymentStatus.ADMIN_REQUIRED);
        assertThat(unchanged.getAmount()).isEqualTo(targetOrder.getAmount());
    }

    private Party startTwoRiderPartyAndSubmit(double fareFactor) {
        demo.openBoardParty(joiner);
        Party party = parties.findAll().getFirst();
        long payerId = party.getPayer().getId();
        long passengerId = nonPayerIds(party).getFirst();

        clock.moveTo(party.getDepartureAt());
        readiness.call(payerId, party.getId());
        boarding.open(payerId, party.getId());
        var code = boarding.state(payerId, party.getId());
        boarding.confirm(passengerId, party.getId(), code.code(), code.generation());
        boarding.start(payerId, party.getId());
        clock.moveTo(code.deadlineAt());
        lifecycle.afterDepartures();

        long actualFare = Math.round(party.getExpectedTotal() * fareFactor);
        settlements.submit(payerId, party.getId(), actualFare, receipt(), null, null);
        return parties.findById(party.getId()).orElseThrow();
    }

    private Party startThreeRiderPartyAndSubmit(double fareFactor) throws Exception {
        demo.openBoardParty(joiner);
        Party party = parties.findAll().getFirst();
        mvc.perform(post("/api/board/parties/{id}/join", party.getId()).session(session(joiner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pointName":"시장 입구","pointLat":37.8330,"pointLon":127.1460}
                                """))
                .andExpect(status().isOk());

        long payerId = party.getPayer().getId();
        List<Long> riders = nonPayerIds(party);
        clock.moveTo(party.getDepartureAt());
        readiness.call(payerId, party.getId());
        boarding.open(payerId, party.getId());
        var code = boarding.state(payerId, party.getId());
        for (long riderId : riders) {
            boarding.confirm(riderId, party.getId(), code.code(), code.generation());
        }
        boarding.start(payerId, party.getId());
        clock.moveTo(code.deadlineAt());
        lifecycle.afterDepartures();

        Party reloaded = parties.findById(party.getId()).orElseThrow();
        long actualFare = Math.round(reloaded.getExpectedTotal() * fareFactor);
        settlements.submit(payerId, reloaded.getId(), actualFare, receipt(), null, null);
        return parties.findById(reloaded.getId()).orElseThrow();
    }

    private List<Long> nonPayerIds(Party party) {
        return requests.findByPartyOrderByRideOrder(party).stream()
                .map(r -> r.getMember().getId())
                .filter(id -> !id.equals(party.getPayer().getId()))
                .toList();
    }

    private PaymentOrder postpaidOrder(long partyId, long memberId) {
        return orders.findByPartyIdAndMemberIdAndPurpose(partyId, memberId, PaymentPurpose.POSTPAID).orElseThrow();
    }

    private MockHttpSession session(long memberId) {
        var session = new MockHttpSession();
        AuthenticatedTestSession.authenticate(session, memberId, loginTokens);
        return session;
    }

    private long demoMember(int index) {
        return members.findByEmail("demo-f" + index + "@daejin.ac.kr").orElseThrow().getId();
    }

    private MockMultipartFile receipt() {
        return new MockMultipartFile("receipt", "receipt.png", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1});
    }

    private String confirmBody(int revision, String orderId, long amount, String paymentKey) {
        if (paymentKey == null) {
            return String.format("{\"revision\":%d,\"orderId\":\"%s\",\"amount\":%d}", revision, orderId, amount);
        }
        return String.format("{\"revision\":%d,\"orderId\":\"%s\",\"amount\":%d,\"paymentKey\":\"%s\"}",
                revision, orderId, amount, paymentKey);
    }
}
