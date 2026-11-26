package com.campustaximate.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campustaximate.matching.Direction;
import com.campustaximate.matching.GeoPoint;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import com.campustaximate.server.route.RouteClient;
import com.campustaximate.server.route.SimulatedRouteClient;
import com.campustaximate.server.service.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:postpaid-matching;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.kakao.rest-key=", "app.tmap.app-key=", "app.toss.secret-key=", "app.mail.enabled=false",
        "app.fare.quote-bias=0", "app.fare.quote-margin=0.50", "app.upload-dir=target/postpaid-matching-uploads"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@TestPropertySource(properties = "app.payment.flow-version=3")
@Import(PeopleBoardFlowTest.FixedTime.class)
class PostpaidMatchingAndJoinTest {
    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
    private static final GeoPoint JOIN_STOP = new GeoPoint(37.8330, 127.1460);

    @Autowired private AppClock clock;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RouteClient routes;
    @Autowired private DemoService demo;
    @Autowired private SlotService slots;
    @Autowired private LoginTokenService loginTokens;
    @Autowired private PartyJoinService joins;
    @Autowired private PartyService views;
    @Autowired private PartyReadinessService readiness;
    @Autowired private BoardingCodeService boarding;
    @Autowired private RideRequestService rideRequests;
    @Autowired private PaymentService payments;
    @Autowired private PaymentMethodService paymentMethods;
    @Autowired private PaymentLifecycleService lifecycle;
    @Autowired private SettlementService settlements;
    @Autowired private MemberRepository members;
    @Autowired private HubRepository hubs;
    @Autowired private PartyRepository parties;
    @Autowired private RideRequestRepository requests;
    @Autowired private PartyJoinRepository joinRecords;
    @Autowired private PartyMemberTermRepository terms;
    @Autowired private PartyChangeRepository changes;
    @Autowired private PaymentOrderRepository orders;
    @Autowired private SettlementRepository settlementRecords;
    @Autowired private CompensationEntryRepository compensation;
    @Autowired private PayoutLedgerRepository payouts;
    @Autowired private ReserveEntryRepository reserve;
    private long joiner;

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
    }

    @Test
    void reservationGoesStraightToReadyAndCanCallWithoutPrepayment() {
        Party party = matchReservation();
        assertPostpaidMatch(party);
        assertThat(party.getFreeCancelUntil()).isEqualTo(PaymentService.prepayDeadline(
                party.getConfirmedAt(), party.getDepartureAt(), false));
        long payer = party.getPayer().getId();
        long passenger = otherMember(party).getMember().getId();
        clock.moveTo(party.getDepartureAt().minusSeconds(600));

        var view = views.view(payer, party.getId());
        assertThat(view.callReady()).isTrue();
        assertThat(view.canCall()).isTrue();
        assertThat(view.notReadyReasons()).isEmpty();
        assertThat(view.canArrive()).isTrue();
        assertThat(view.canCancel()).isTrue();
        assertThat(view.canSettle()).isFalse();
        assertThat(view.canBoard()).isFalse();
        assertThat(view.members()).noneMatch(PartyService.MemberView::prepaid);
        assertThat(views.view(passenger, party.getId()).myPrepaid()).isFalse();
        assertThat(views.view(passenger, party.getId()).canCall()).isFalse();

        readiness.call(payer, party.getId());
        assertThat(views.view(payer, party.getId()).canBoard()).isTrue();
        assertThat(views.view(passenger, party.getId()).canBoard()).isFalse();
    }

    @Test
    void instantMatchUsesFiveMinutesOfFreeCancellationAndArrivalReadiness() {
        Hub hub = hubs.findAll().stream().filter(h -> h.isActive() && h.isFieldChecked()).findFirst().orElseThrow();
        for (int i = 1; i <= 2; i++) {
            rideRequests.create(demoMember(i), new RideRequestService.NewRequest(Direction.FROM_HUB, hub.getId(),
                    null, null, null, "시장 입구", JOIN_STOP.lat(), JOIN_STOP.lon(), null,
                    RequestMode.INSTANT, TimeBasis.DEPART, new RideRequestService.Origin(hub.getLat(), hub.getLon(), hub.getName()),
                    List.of(hub.getId()), null));
        }
        Instant slot = NOW.plusSeconds(60);
        clock.moveTo(slot);
        slots.runLocked(() -> {
            slots.process(slot);
            return null;
        });
        Party party = parties.findAll().getFirst();
        assertPostpaidMatch(party);
        assertThat(party.isInstant()).isTrue();
        assertThat(party.getConfirmedAt()).isEqualTo(slot);
        assertThat(party.getFreeCancelUntil()).isEqualTo(slot.plusSeconds(300)).isAfter(clock.now());

        long payer = party.getPayer().getId();
        var beforeArrival = views.view(payer, party.getId());
        assertThat(beforeArrival.callReady()).isFalse();
        assertThat(beforeArrival.notReadyReasons()).hasSize(1).allMatch(reason -> reason.contains("도착"));
        for (var rider : beforeArrival.members()) readiness.prepare(rider.memberId(), party.getId(), "NEARBY");
        var afterArrival = views.view(payer, party.getId());
        assertThat(afterArrival.callReady()).isTrue();
        assertThat(afterArrival.canCall()).isTrue();
        assertThat(afterArrival.notReadyReasons()).isEmpty();
    }

    @Test
    void configExposesPaymentVersionAndSimulatedGateway() throws Exception {
        mvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentFlowVersion").value(3))
                .andExpect(jsonPath("$.simulatedPayment").value(true));
    }

    @Test
    void postpaidQuoteOmitsConfiguredMarginForOneOrTwoStops() {
        var raw = new SimulatedRouteClient();
        var origin = new GeoPoint(37.8707, 127.1561);
        var last = new GeoPoint(37.8150, 127.1390);
        for (var stops : List.of(List.of(last), List.of(JOIN_STOP, last))) {
            assertThat(routes.sharedRouteAt(origin, stops, NOW).fare()).isEqualTo(raw.routeAt(origin, stops, NOW).fare());
        }
    }

    @Test
    void boardJoinImmediatelyUpdatesMembershipAndRejectsDuplicates() throws Exception {
        Party party = matchReservation();
        var originalTerms = terms.findByPartyIdOrderById(party.getId()).stream().map(PartyMemberTerm::getId).toList();
        var session = new MockHttpSession();
        AuthenticatedTestSession.authenticate(session, joiner, loginTokens);
        mvc.perform(post("/api/board/parties/{id}/join", party.getId()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pointName":"시장 입구","pointLat":37.8330,"pointLon":127.1460}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentOrderId").doesNotExist());
        var join = joinRecords.findByMemberIdOrderByIdDesc(joiner).getFirst();
        assertConfirmedJoin(party, join);
        assertThat(terms.findByPartyIdOrderById(party.getId())).hasSize(3)
                .extracting(PartyMemberTerm::getId).containsAll(originalTerms);
        assertThat(changes.findByPartyIdOrderByGroupVersion(party.getId()))
                .extracting(PartyChange::getReason).containsExactly("JOIN_CONFIRMED");
        assertJoinConflict(party.getId(), join.getRequestId(), "JOIN_ALREADY_ATTEMPTED");
        assertThat(joinRecords.count()).isEqualTo(1);
    }

    @Test
    void automaticJoinUsesTheSameImmediateCompletion() {
        Party party = matchReservation();
        RideRequest waiting = requestJoin(party);
        joins.offerWaiting(clock.now(), List.of(waiting));
        assertConfirmedJoin(party, joinRecords.findByMemberIdOrderByIdDesc(joiner).getFirst());
    }

    @Test
    void missingPaymentMethodBlocksDirectAndAutomaticJoin() {
        Party party = matchReservation();
        RideRequest waiting = requestJoin(party);
        // A waiting request can predate the v3 payment-method requirement.
        jdbc.update("delete from payment_method where member_id = ?", joiner);
        assertJoinConflict(party.getId(), waiting.getId(), "PAYMENT_METHOD_REQUIRED");
        joins.offerWaiting(clock.now(), List.of(waiting));
        assertThat(requests.findById(waiting.getId()).orElseThrow().getStatus()).isEqualTo(RequestStatus.WAITING);
        assertThat(joinRecords.count()).isZero();
        assertThat(terms.findByPartyIdOrderById(party.getId())).hasSize(2);
    }

    @Test
    void overduePaymentBlocksAnExistingWaitingRequestFromJoining() {
        Party party = matchReservation();
        RideRequest waiting = requestJoin(party);
        var former = parties.save(new Party(party.getDirection(), party.getHub(), null, party.getPayer(), NOW.minusSeconds(3600), 10_000));
        payments.createObligation(former.getId(), joiner, null, null, PaymentPurpose.COMPENSATION, 1_000, clock.now().minusSeconds(1));
        assertJoinConflict(party.getId(), waiting.getId(), "ADDITIONAL_PAYMENT_OVERDUE");
        assertThat(joinRecords.count()).isZero();
    }

    @Test
    void settlementFinalizesWithoutCreatingReserve() {
        Party party = matchReservation();
        long payer = party.getPayer().getId();
        long passenger = otherMember(party).getMember().getId();
        clock.moveTo(party.getDepartureAt());
        readiness.call(payer, party.getId());
        boarding.open(payer, party.getId());
        var code = boarding.state(payer, party.getId());
        boarding.confirm(passenger, party.getId(), code.code(), code.generation());
        boarding.start(payer, party.getId());
        clock.moveTo(code.deadlineAt());
        lifecycle.afterDepartures();
        assertThat(views.view(payer, party.getId()).canSettle()).isTrue();
        settlements.submitForDemo(payer, party.getId(), party.getExpectedTotal(), null, null);
        var settlement = settlementRecords.findByParty(party).orElseThrow();
        clock.moveTo(settlement.getObjectionDeadlineAt());
        lifecycle.afterDepartures();

        assertThat(parties.findById(party.getId()).orElseThrow().getStatus()).isEqualTo(PartyStatus.SETTLED);
        assertThat(settlementRecords.findById(settlement.getId()).orElseThrow().getFinalizedAt()).isEqualTo(clock.now());
        assertThat(orders.findByPartyIdOrderById(party.getId())).isNotEmpty()
                .noneMatch(order -> order.getPurpose() == PaymentPurpose.PREPAY);
        assertThat(payouts.findBySettlementId(settlement.getId())).isPresent();
        assertThat(reserve.count()).isZero();
    }

    @Test
    void lateCancellationDissolvesAndCreatesOneUncollectedCompensationCharge() {
        Party party = matchReservation();
        RideRequest leaving = otherMember(party);
        clock.moveTo(party.getFreeCancelUntil().plusSeconds(1));
        rideRequests.cancel(leaving.getMember().getId(), leaving.getId());
        payments.refundForDissolution(party.getId());
        lifecycle.beforeDepartures();

        assertThat(parties.findById(party.getId()).orElseThrow().getStatus()).isEqualTo(PartyStatus.DISSOLVED);
        var charge = compensation.findByPartyIdOrderByRequestId(party.getId()).stream()
                .filter(entry -> entry.getRequestId().equals(leaving.getId())).findFirst().orElseThrow();
        assertThat(charge.getCharge()).isPositive();
        var ledger = payouts.findByPartyId(party.getId()).orElseThrow();
        assertThat(ledger.getExpectedAmount()).isEqualTo(charge.getCharge());
        assertThat(ledger.getCollectedAmount()).isZero();
        assertThat(ledger.getAmount()).isZero();
        var partyOrders = orders.findByPartyIdOrderById(party.getId());
        assertThat(partyOrders).hasSize(1);
        var order = partyOrders.getFirst();
        assertThat(order.getPurpose()).isEqualTo(PaymentPurpose.COMPENSATION);
        assertThat(order.getAmount()).isEqualTo(charge.getCharge());
        clock.moveTo(order.getDeadlineAt());
        lifecycle.afterDepartures();
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.OVERDUE);
        assertThat(payments.overdue(order.getMemberId())).isTrue();
        for (int retry = 0; retry < 2; retry++) {
            assertThat(payments.confirm(order.getMemberId(), order.getOrderId(), "test_" + order.getOrderId(), order.getAmount()).status())
                    .isEqualTo(PaymentStatus.APPROVED);
        }
        assertThat(payouts.findByPartyId(party.getId()).orElseThrow().getCollectedAmount()).isEqualTo(charge.getCharge());
        assertThat(payments.overdue(order.getMemberId())).isFalse();
        assertThat(parties.findById(party.getId()).orElseThrow().getStatus()).isEqualTo(PartyStatus.DISSOLVED);
    }

    @Test
    void immediateJoinSyncsExistingRidersConfirmedShare() throws Exception {
        Party party = matchReservation();
        long payerId = party.getPayer().getId();
        long passengerId = otherMember(party).getMember().getId();

        var session = new MockHttpSession();
        AuthenticatedTestSession.authenticate(session, joiner, loginTokens);
        mvc.perform(post("/api/board/parties/{id}/join", party.getId()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pointName":"시장 입구","pointLat":37.8330,"pointLon":127.1460}
                                """))
                .andExpect(status().isOk());

        for (long memberId : List.of(payerId, passengerId, joiner)) {
            RideRequest activeRequest = requests.findByActiveMemberId(memberId).orElseThrow();
            var term = terms.findByPartyIdAndMemberId(party.getId(), memberId).orElseThrow();
            assertThat(term.getConfirmedShare()).isEqualTo(activeRequest.getExpectedShare());
            assertThat(views.view(memberId, party.getId()).myConfirmedShare()).isEqualTo(activeRequest.getExpectedShare());
        }
    }

    @Test
    void threeRiderLateCancelCreatesCompensationForDepartedMemberAndTracksPayoutLedger() throws Exception {
        Party party = matchReservation();
        long payerId = party.getPayer().getId();
        RideRequest staying = otherMember(party);
        long stayingId = staying.getMember().getId();

        var session = new MockHttpSession();
        AuthenticatedTestSession.authenticate(session, joiner, loginTokens);
        mvc.perform(post("/api/board/parties/{id}/join", party.getId()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pointName":"시장 입구","pointLat":37.8330,"pointLon":127.1460}
                                """))
                .andExpect(status().isOk());

        RideRequest leaving = requests.findByActiveMemberId(joiner).orElseThrow();
        clock.moveTo(party.getFreeCancelUntil().plusSeconds(1));
        rideRequests.cancel(joiner, leaving.getId());
        lifecycle.beforeDepartures();

        Party reloaded = parties.findById(party.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PartyStatus.READY);

        clock.moveTo(reloaded.getDepartureAt());
        readiness.call(payerId, reloaded.getId());
        boarding.open(payerId, reloaded.getId());
        var code = boarding.state(payerId, reloaded.getId());
        boarding.confirm(stayingId, reloaded.getId(), code.code(), code.generation());
        boarding.start(payerId, reloaded.getId());
        clock.moveTo(code.deadlineAt());
        lifecycle.afterDepartures();

        settlements.submitForDemo(payerId, reloaded.getId(), reloaded.getExpectedTotal(), null, null);
        var settlement = settlementRecords.findByParty(reloaded).orElseThrow();
        clock.moveTo(settlement.getObjectionDeadlineAt());
        lifecycle.afterDepartures();

        assertThat(parties.findById(reloaded.getId()).orElseThrow().getStatus()).isEqualTo(PartyStatus.SETTLED);
        var entries = compensation.findBySettlementIdOrderByRequestId(settlement.getId());
        long totalCredits = entries.stream().mapToLong(CompensationEntry::getCredit).sum();
        long totalCharges = entries.stream().mapToLong(CompensationEntry::getCharge).sum();
        assertThat(totalCharges).isPositive().isEqualTo(totalCredits);

        PaymentOrder compOrder = orders.findByPartyIdAndMemberIdAndPurpose(reloaded.getId(), joiner, PaymentPurpose.COMPENSATION)
                .orElseThrow();
        assertThat(compOrder.getAmount()).isEqualTo(totalCharges);
        assertThat(compOrder.getStatus()).isEqualTo(PaymentStatus.REQUIRED);

        PaymentOrder stayingOrder = orders.findByPartyIdOrderById(reloaded.getId()).stream()
                .filter(o -> o.getMemberId().equals(stayingId) && o.getPurpose() != PaymentPurpose.PREPAY)
                .findFirst().orElseThrow();

        var ledgerBefore = payouts.findBySettlementId(settlement.getId()).orElseThrow();
        long expectedPayout = compOrder.getAmount() + stayingOrder.getAmount();
        assertThat(ledgerBefore.getExpectedAmount()).isEqualTo(expectedPayout);
        assertThat(ledgerBefore.getCollectedAmount()).isZero();
        assertThat(ledgerBefore.getAmount()).isZero();

        payments.confirm(stayingId, stayingOrder.getOrderId(), "pay_" + stayingOrder.getOrderId(), stayingOrder.getAmount());
        assertThat(payouts.findBySettlementId(settlement.getId()).orElseThrow().getCollectedAmount())
                .isEqualTo(stayingOrder.getAmount());
        assertThat(parties.findById(reloaded.getId()).orElseThrow().getStatus()).isEqualTo(PartyStatus.SETTLED);

        payments.confirm(joiner, compOrder.getOrderId(), "pay_" + compOrder.getOrderId(), compOrder.getAmount());
        var ledgerAfter = payouts.findBySettlementId(settlement.getId()).orElseThrow();
        assertThat(ledgerAfter.getCollectedAmount()).isEqualTo(expectedPayout);
        assertThat(ledgerAfter.getAmount()).isEqualTo(expectedPayout);
        assertThat(parties.findById(reloaded.getId()).orElseThrow().getStatus()).isEqualTo(PartyStatus.COMPLETED);
    }

    private Party matchReservation() {
        demo.openBoardParty(joiner);
        return parties.findAll().getFirst();
    }

    private long demoMember(int index) {
        return members.findByEmail("demo-f" + index + "@daejin.ac.kr").orElseThrow().getId();
    }

    private RideRequest otherMember(Party party) {
        return requests.findByPartyOrderByRideOrder(party).stream()
                .filter(request -> !request.getMember().getId().equals(party.getPayer().getId())).findFirst().orElseThrow();
    }

    private RideRequest requestJoin(Party party) {
        return rideRequests.create(joiner, new RideRequestService.NewRequest(Direction.FROM_HUB, party.getHub().getId(),
                party.getDepartureAt(), party.getDepartureAt().plusSeconds(1800), null, "시장 입구", JOIN_STOP.lat(), JOIN_STOP.lon(), null));
    }

    private void assertPostpaidMatch(Party party) {
        assertThat(party.getPaymentFlowVersion()).isEqualTo(3);
        assertThat(party.getStatus()).isEqualTo(PartyStatus.READY);
        assertThat(party.getOriginalPayerId()).isEqualTo(party.getPayer().getId());
        assertThat(party.getPrepayDeadlineAt()).isNull();
        assertThat(party.getFreeCancelUntil()).isNotNull();
        assertThat(orders.findByPartyIdOrderById(party.getId())).isEmpty();
        assertThat(terms.findByPartyIdOrderById(party.getId())).hasSize(2).allSatisfy(term -> {
            assertThat(term.getConfirmedShare()).isPositive();
            assertThat(term.getConfirmedPrepay()).isZero();
            assertThat(term.isMigratedUnknown()).isFalse();
        });
    }

    private void assertJoinConflict(long partyId, long requestId, String code) {
        assertThatThrownBy(() -> joins.offer(joiner, partyId, requestId))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo(code);
                });
    }

    private void assertConfirmedJoin(Party original, PartyJoin join) {
        assertThat(join.getState()).isEqualTo("CONFIRMED");
        assertThat(join.getPaymentOrderId()).isNull();
        var party = parties.findById(original.getId()).orElseThrow();
        assertThat(party.getPendingJoinId()).isNull();
        assertThat(party.getGroupVersion()).isEqualTo(original.getGroupVersion() + 1);
        assertThat(party.getExpectedTotal()).isEqualTo(join.getTotalFare());
        var newcomer = requests.findById(join.getRequestId()).orElseThrow();
        assertThat(newcomer.getStatus()).isEqualTo(RequestStatus.MATCHED);
        assertThat(newcomer.getPendingJoinId()).isNull();
        assertThat(newcomer.getParty().getId()).isEqualTo(original.getId());
        assertThat(terms.findByPartyIdAndMemberId(party.getId(), joiner)).hasValueSatisfying(term -> {
            assertThat(term.getConfirmedShare()).isEqualTo(newcomer.getExpectedShare());
            assertThat(term.getConfirmedPrepay()).isZero();
        });
        assertThat(orders.findByPartyIdOrderById(party.getId())).isEmpty();
    }
}
