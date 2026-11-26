package com.campustaximate.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campustaximate.matching.Direction;
import com.campustaximate.matching.GeoPoint;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.BoardingCase;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.PartyStatus;
import com.campustaximate.server.domain.PaymentOrder;
import com.campustaximate.server.domain.PaymentPurpose;
import com.campustaximate.server.domain.PaymentStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.domain.Settlement;
import com.campustaximate.server.domain.SettlementShare;
import com.campustaximate.server.repository.BoardingCaseRepository;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.repository.PartyRepository;
import com.campustaximate.server.repository.PaymentOrderRepository;
import com.campustaximate.server.repository.PayoutLedgerRepository;
import com.campustaximate.server.repository.ReserveEntryRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.repository.SettlementRepository;
import com.campustaximate.server.repository.SettlementShareRepository;
import com.campustaximate.server.service.AdminService;
import com.campustaximate.server.service.BoardingCodeService;
import com.campustaximate.server.service.DemoService;
import com.campustaximate.server.service.LoginTokenService;
import com.campustaximate.server.service.NoShowService;
import com.campustaximate.server.service.PartyReadinessService;
import com.campustaximate.server.service.PaymentLifecycleService;
import com.campustaximate.server.service.PaymentMethodService;
import com.campustaximate.server.service.PaymentService;
import com.campustaximate.server.service.PostpaidOrderSyncService;
import com.campustaximate.server.service.RideRequestService;
import com.campustaximate.server.service.SettlementService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:postpaid-calc;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.kakao.rest-key=", "app.tmap.app-key=", "app.toss.secret-key=", "app.mail.enabled=false",
        "app.fare.quote-bias=0", "app.fare.quote-margin=0.50", "app.upload-dir=target/postpaid-calc-uploads"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@TestPropertySource(properties = "app.payment.flow-version=3")
@Import(PeopleBoardFlowTest.FixedTime.class)
class PostpaidSettlementCalculationTest {
    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    @Autowired private AppClock clock;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DemoService demo;
    @Autowired private LoginTokenService loginTokens;
    @Autowired private PartyReadinessService readiness;
    @Autowired private BoardingCodeService boarding;
    @Autowired private NoShowService noShows;
    @Autowired private RideRequestService rideRequests;
    @Autowired private PaymentService payments;
    @Autowired private PaymentMethodService paymentMethods;
    @Autowired private PaymentLifecycleService lifecycle;
    @Autowired private PostpaidOrderSyncService postpaidSync;
    @Autowired private SettlementService settlements;
    @Autowired private AdminService adminService;
    @Autowired private MemberRepository members;
    @Autowired private PartyRepository parties;
    @Autowired private RideRequestRepository requests;
    @Autowired private PaymentOrderRepository orders;
    @Autowired private SettlementRepository settlementRecords;
    @Autowired private SettlementShareRepository shares;
    @Autowired private BoardingCaseRepository boardingCases;
    @Autowired private PayoutLedgerRepository payouts;
    @Autowired private ReserveEntryRepository reserve;

    private long joiner;
    private long adminId;

    @BeforeEach
    void reset() {
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

    @Test
    void actualFareSplitUsesMeteredAmountInsteadOfExpectedTotal() {
        Party party = boardAndStartTwoRiders();
        long payerId = party.getPayer().getId();
        long passengerId = otherMember(party).getMember().getId();
        long expected = party.getExpectedTotal();
        long actualFare = Math.round(expected * 1.25);

        settlements.submit(payerId, party.getId(), actualFare, receipt(), null, null);
        Settlement settlement = settlementRecords.findByParty(party).orElseThrow();

        var shareList = shares.findBySettlementIdOrderByRideOrder(settlement.getId());
        assertThat(shareList).hasSize(2);
        assertThat(shareList.stream().mapToLong(SettlementShare::getAmount).sum()).isEqualTo(actualFare);

        long passengerShare = shareList.stream().filter(s -> s.getMemberId().equals(passengerId))
                .findFirst().orElseThrow().getAmount();
        PaymentOrder postpaid = orders.findByPartyIdAndMemberIdAndPurpose(party.getId(), passengerId, PaymentPurpose.POSTPAID)
                .orElseThrow();
        assertThat(postpaid.getAmount()).isEqualTo(passengerShare);
        assertThat(postpaid.getSettlementRevision()).isEqualTo(0);
        assertThat(postpaid.getStatus()).isEqualTo(PaymentStatus.REQUIRED);
        assertThat(orders.findByPartyIdAndMemberIdAndPurpose(party.getId(), passengerId, PaymentPurpose.PREPAY)).isEmpty();
        assertThat(orders.findByPartyIdAndMemberIdAndPurpose(party.getId(), passengerId, PaymentPurpose.ADDITIONAL)).isEmpty();
    }

    @Test
    void sixHourAutoConfirmAndTwentyFourHourOverdueAcrossAdminAndRequestGuard() {
        Party party = boardAndStartTwoRiders();
        long payerId = party.getPayer().getId();
        long passengerId = otherMember(party).getMember().getId();
        long actualFare = party.getExpectedTotal() + 1_800;

        settlements.submit(payerId, party.getId(), actualFare, receipt(), null, null);
        Settlement settlement = settlementRecords.findByParty(party).orElseThrow();
        Instant storedAt = settlement.getStoredAt();
        assertThat(settlement.getObjectionDeadlineAt()).isEqualTo(storedAt.plus(Duration.ofHours(6)));

        clock.moveTo(storedAt.plus(Duration.ofHours(6)).minusSeconds(1));
        lifecycle.afterDepartures();
        assertThat(parties.findById(party.getId()).orElseThrow().getStatus()).isEqualTo(PartyStatus.OBJECTION_WINDOW);

        clock.moveTo(storedAt.plus(Duration.ofHours(6)));
        lifecycle.afterDepartures();

        Party settled = parties.findById(party.getId()).orElseThrow();
        Settlement finalized = settlementRecords.findById(settlement.getId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(PartyStatus.SETTLED);
        assertThat(finalized.getFinalizedAt()).isEqualTo(clock.now());

        PaymentOrder postpaid = orders.findByPartyIdAndMemberIdAndPurpose(party.getId(), passengerId, PaymentPurpose.POSTPAID)
                .orElseThrow();
        assertThat(postpaid.getDeadlineAt()).isEqualTo(finalized.getFinalizedAt().plusSeconds(24 * 3600L));
        assertThat(postpaid.getStatus()).isEqualTo(PaymentStatus.REQUIRED);

        var ledger = payouts.findBySettlementId(finalized.getId()).orElseThrow();
        assertThat(ledger.getExpectedAmount()).isEqualTo(postpaid.getAmount());
        assertThat(ledger.getCollectedAmount()).isZero();
        assertThat(reserve.count()).isZero();

        clock.moveTo(postpaid.getDeadlineAt().minusSeconds(1));
        lifecycle.afterDepartures();
        assertThat(orders.findById(postpaid.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REQUIRED);
        assertThat(adminService.overdue(adminId)).isEmpty();
        assertThat(payments.overdue(passengerId)).isFalse();
        assertThat(rideRequests.isRestricted(passengerId)).isFalse();

        clock.moveTo(postpaid.getDeadlineAt());
        lifecycle.afterDepartures();
        assertThat(orders.findById(postpaid.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.OVERDUE);
        assertThat(adminService.overdue(adminId)).extracting(PaymentOrder::getId).containsExactly(postpaid.getId());
        assertThat(payments.overdue(passengerId)).isTrue();
        assertThat(rideRequests.isRestricted(passengerId)).isTrue();
    }

    @Test
    void unresolvedNoShowCaseLocksPostpaidAndDecisionUnlocksWithUpdatedShareAndOrderId() throws Exception {
        Party party = matchReservation();
        long payerId = party.getPayer().getId();
        long stayingId = otherMember(party).getMember().getId();

        var session = new MockHttpSession();
        AuthenticatedTestSession.authenticate(session, joiner, loginTokens);
        mvc.perform(post("/api/board/parties/{id}/join", party.getId()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pointName":"시장 입구","pointLat":37.8330,"pointLon":127.1460}
                                """))
                .andExpect(status().isOk());

        clock.moveTo(party.getDepartureAt());
        readiness.call(payerId, party.getId());
        boarding.open(payerId, party.getId());
        var code = boarding.state(payerId, party.getId());
        // Only the original passenger enters the code; the joiner misses boarding.
        boarding.confirm(stayingId, party.getId(), code.code(), code.generation());
        boarding.start(payerId, party.getId());
        clock.moveTo(code.deadlineAt());
        lifecycle.afterDepartures();

        BoardingCase pendingCase = boardingCases.findByPartyIdOrderByRequestId(party.getId()).stream()
                .filter(c -> c.getMemberId().equals(joiner)).findFirst().orElseThrow();
        assertThat(pendingCase.pending()).isTrue();
        noShows.vote(payerId, party.getId(), joiner, false);
        noShows.vote(stayingId, party.getId(), joiner, false);

        Party reloaded = parties.findById(party.getId()).orElseThrow();
        long actualFare = reloaded.getExpectedTotal();
        settlements.submit(payerId, reloaded.getId(), actualFare, receipt(), null, null);
        Settlement settlement = settlementRecords.findByParty(reloaded).orElseThrow();

        assertThat(postpaidSync.isPayableLocked(parties.findById(reloaded.getId()).orElseThrow(), settlement)).isTrue();
        PaymentOrder lockedOrder = orders.findByPartyIdAndMemberIdAndPurpose(reloaded.getId(), stayingId, PaymentPurpose.POSTPAID)
                .orElseThrow();
        String originalOrderId = lockedOrder.getOrderId();
        long uncreditedAmount = lockedOrder.getAmount();
        assertThat(lockedOrder.getSettlementRevision()).isEqualTo(0);

        clock.moveTo(pendingCase.getDeadlineAt());
        lifecycle.afterDepartures();

        Party finalizedParty = parties.findById(reloaded.getId()).orElseThrow();
        assertThat(finalizedParty.getStatus()).isEqualTo(PartyStatus.SETTLED);
        Settlement updatedSettlement = settlementRecords.findById(settlement.getId()).orElseThrow();
        assertThat(postpaidSync.isPayableLocked(finalizedParty, updatedSettlement)).isFalse();

        PaymentOrder unlockedOrder = orders.findById(lockedOrder.getId()).orElseThrow();
        assertThat(unlockedOrder.getOrderId()).isNotEqualTo(originalOrderId);
        assertThat(unlockedOrder.getAmount()).isLessThan(uncreditedAmount);
        assertThat(unlockedOrder.getSettlementRevision()).isEqualTo(1);

        PaymentOrder compOrder = orders.findByPartyIdAndMemberIdAndPurpose(reloaded.getId(), joiner, PaymentPurpose.COMPENSATION)
                .orElseThrow();
        assertThat(compOrder.getAmount()).isPositive();
        assertThat(compOrder.getStatus()).isEqualTo(PaymentStatus.REQUIRED);
    }

    @Test
    void payerCorrectionRotatesOrderIdUpdatesRevisionAndResetsSixHourWindow() {
        Party party = boardAndStartTwoRiders();
        long payerId = party.getPayer().getId();
        long passengerId = otherMember(party).getMember().getId();
        long firstFare = party.getExpectedTotal();
        long correctedFare = firstFare + 2_000;

        settlements.submit(payerId, party.getId(), firstFare, receipt(), null, null);
        PaymentOrder initialOrder = orders.findByPartyIdAndMemberIdAndPurpose(party.getId(), passengerId, PaymentPurpose.POSTPAID)
                .orElseThrow();
        long rowId = initialOrder.getId();
        String initialOrderId = initialOrder.getOrderId();
        long initialAmount = initialOrder.getAmount();

        clock.moveTo(clock.now().plus(Duration.ofHours(1)));
        settlements.correct(payerId, party.getId(), correctedFare, receipt(), null, null);

        Settlement corrected = settlementRecords.findByParty(party).orElseThrow();
        assertThat(corrected.getRevision()).isEqualTo(1);
        assertThat(corrected.getObjectionDeadlineAt()).isEqualTo(clock.now().plus(Duration.ofHours(6)));

        PaymentOrder rotated = orders.findByPartyIdAndMemberIdAndPurpose(party.getId(), passengerId, PaymentPurpose.POSTPAID)
                .orElseThrow();
        assertThat(rotated.getId()).isEqualTo(rowId);
        assertThat(rotated.getOrderId()).isNotEqualTo(initialOrderId);
        assertThat(rotated.getAmount()).isGreaterThan(initialAmount);
        assertThat(rotated.getSettlementRevision()).isEqualTo(1);
    }

    private Party matchReservation() {
        demo.openBoardParty(joiner);
        return parties.findAll().getFirst();
    }

    private Party boardAndStartTwoRiders() {
        Party party = matchReservation();
        long payerId = party.getPayer().getId();
        long passengerId = otherMember(party).getMember().getId();
        clock.moveTo(party.getDepartureAt());
        readiness.call(payerId, party.getId());
        boarding.open(payerId, party.getId());
        var code = boarding.state(payerId, party.getId());
        boarding.confirm(passengerId, party.getId(), code.code(), code.generation());
        boarding.start(payerId, party.getId());
        clock.moveTo(code.deadlineAt());
        lifecycle.afterDepartures();
        return parties.findById(party.getId()).orElseThrow();
    }

    private long demoMember(int index) {
        return members.findByEmail("demo-f" + index + "@daejin.ac.kr").orElseThrow().getId();
    }

    private RideRequest otherMember(Party party) {
        return requests.findByPartyOrderByRideOrder(party).stream()
                .filter(request -> !request.getMember().getId().equals(party.getPayer().getId()))
                .findFirst().orElseThrow();
    }

    private MockMultipartFile receipt() {
        return new MockMultipartFile("receipt", "receipt.png", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1});
    }
}
