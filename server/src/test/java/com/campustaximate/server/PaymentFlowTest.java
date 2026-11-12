package com.campustaximate.server;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.campustaximate.matching.Direction;
import com.campustaximate.server.common.*;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import com.campustaximate.server.payment.*;
import com.campustaximate.server.service.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 PG 호출 없이 주문·상태·저장된 정산 결과를 검증한다. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:payment-flow;MODE=MySQL;DB_CLOSE_DELAY=-1", "app.upload-dir=target/test-uploads"})
@ActiveProfiles("demo")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PaymentFlowTest {
    private static final Instant NOW = Instant.parse("2026-11-10T08:30:00Z");
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired PaymentService payments;
    @Autowired PaymentLifecycleService lifecycle;
    @Autowired SettlementService settlementService;
    @Autowired SlotService slots;
    @Autowired PartyRepository parties;
    @Autowired RideRequestRepository requests;
    @Autowired MemberRepository members;
    @Autowired HubRepository hubs;
    @Autowired SettlementRepository settlements;
    @Autowired TransferRequestRepository transfers;
    @Autowired PaymentOrderRepository orders;
    @Autowired PaymentOperationRepository operations;
    @Autowired SettlementShareRepository allocations;
    @Autowired PayoutLedgerRepository payouts;
    @Autowired RideRequestService rideRequests;
    @Autowired RideConfirmationService rideConfirmations;
    @Autowired PartyMemberTermRepository terms;
    @Autowired PartyChangeRepository partyChanges;
    @Autowired RideConfirmationRepository rideAnswers;
    @Autowired PartyService partyService;
    @Autowired PartyReadinessService readiness;
    @Autowired PartyJoinService joins;
    @Autowired PartyJoinRepository partyJoins;
    @SpyBean PartyChangeService changeService;
    @Autowired NoShowService noShows;
    @Autowired AdminService admin;
    @Autowired BoardingCaseRepository boardingCases;
    @Autowired BoardingVoteRepository boardingVotes;
    @Autowired BoardingExplanationRepository boardingExplanations;
    @Autowired CompensationEntryRepository compensationEntries;
    @Autowired AdminAuditRepository adminAudits;
    @Autowired MemberRestrictionRepository memberRestrictions;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockBean PaymentGateway gateway;
    @SpyBean AppClock clock;
    @SpyBean com.campustaximate.server.route.RouteClient routeClient;
    SimulatedPaymentGateway provider;
    long partyId;
    List<Long> ids;

    @BeforeEach
    void prepare() {
        PaymentTestSupport.clear(jdbc); transfers.deleteAll(); settlements.deleteAll(); requests.deleteAll(); parties.deleteAll();
        at(NOW); jdbc.update("delete from mail_outbox");
        jdbc.update("delete from member_restriction"); jdbc.update("update member set no_show_count=0");
        provider = new SimulatedPaymentGateway();
        doReturn(true).when(gateway).simulated();
        doAnswer(c -> provider.confirm(c.getArgument(0), c.getArgument(1), c.getArgument(2), c.getArgument(3)))
                .when(gateway).confirm(anyString(), anyString(), anyLong(), anyString());
        doAnswer(c -> provider.cancel(c.getArgument(0), c.getArgument(1), c.getArgument(2), c.getArgument(3)))
                .when(gateway).cancel(anyString(), anyLong(), anyString(), anyString());
        doAnswer(c -> provider.get(c.getArgument(0))).when(gateway).get(anyString());
        doAnswer(c -> provider.getByOrderId(c.getArgument(0))).when(gateway).getByOrderId(anyString());
        ids = java.util.stream.IntStream.rangeClosed(1,3).mapToObj(i -> members.findByEmail("demo-f" + i + "@daejin.ac.kr").orElseThrow().getId()).toList();
        partyId = new TransactionTemplate(manager).execute(status -> {
            Party party = parties.save(new Party(Direction.FROM_HUB, hubs.findAll().getFirst(), null,
                    members.findById(ids.get(2)).orElseThrow(), NOW.plusSeconds(1800), 16800));
            long[] fares={6000,10500,15000}, expected={3200,5600,8000};
            List<RideRequest> riders = new ArrayList<>();
            for(int i=0;i<3;i++) {
                RideRequest r=new RideRequest(); r.setMember(members.findById(ids.get(i)).orElseThrow());
                r.setActiveMemberId(ids.get(i)); r.setDirection(Direction.FROM_HUB); r.setHub(party.getHub());
                r.setPointName("도착지 " + i); r.setPointLat(37.785-i*0.04); r.setPointLon(127.105-i*0.02);
                r.setSoloFare(fares[i]); r.setSoloKm(10+i*5); r.setSoloSeconds(1200); r.setRatioUnits(fares[i]);
                r.setExpectedShare(expected[i]); r.setRideOrder(i+1); r.setParty(party); r.setStatus(RequestStatus.MATCHED); r.setArrived(true);
                r.setWindowStart(party.getDepartureAt()); r.setWindowEnd(party.getDepartureAt().plusSeconds(1800)); r.setRequestedAt(NOW);
                riders.add(requests.save(r));
            }
            payments.preparePrepayments(party,riders,NOW);
            return party.getId();
        });
    }
    @AfterEach void clean() {
        PaymentTestSupport.clear(jdbc); transfers.deleteAll(); settlements.deleteAll(); requests.deleteAll(); parties.deleteAll();
    }
    void at(Instant time) { doReturn(time).when(clock).now(); }
    PaymentOrder order(int member) { return orders.findByPartyIdAndMemberIdAndPurpose(partyId,ids.get(member),PaymentPurpose.PREPAY).orElseThrow(); }
    void approve(int member) { PaymentOrder o=order(member); payments.confirm(ids.get(member),o.getOrderId(),"key_"+o.getOrderId(),o.getAmount()); }
    void depart() {
        approve(0); approve(1);
        at(parties.findById(partyId).orElseThrow().getPrepayDeadlineAt()); lifecycle.beforeDepartures();
        at(NOW.plusSeconds(1800)); slots.runLocked(() -> { slots.process(clock.now()); return null; });
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.AWAITING_FARE);
    }
    MockMultipartFile receipt() { return new MockMultipartFile("receipt","r.png","image/png",new byte[]{(byte)0x89,80,78,71,13,10,26,10,1}); }
    void submit(long amount) { settlementService.submit(ids.get(2),partyId,amount,receipt(),"받지 않음","받지 않음"); }
    void finalizeFare() { at(settlements.findAll().getFirst().getObjectionDeadlineAt()); lifecycle.afterDepartures(); }

    @Test void p01Allocates16800AndDoesNotCreateTransfers() {
        depart(); submit(16800);
        assertThat(allocations.findAll().stream().sorted(Comparator.comparingInt(SettlementShare::getRideOrder)).map(SettlementShare::getAmount).toList()).containsExactly(3200L,5600L,8000L);
        assertThat(transfers.count()).isZero(); assertThat(payouts.count()).isZero();
        assertThat(settlements.findAll().getFirst().getAccountNumber()).isNull();
    }
    @Test void p04RefundsOnlyAfterSettlementAndRecordsMockPayout() {
        depart(); submit(16800);
        verify(gateway,never()).cancel(anyString(),anyLong(),anyString(),anyString());
        finalizeFare();
        assertThat(order(0).getRefundedAmount()).isEqualTo(480); assertThat(order(1).getRefundedAmount()).isEqualTo(840);
        verify(gateway).cancel(eq(order(0).getPaymentKey()),eq(480L),anyString(),anyString());
        verify(gateway).cancel(eq(order(1).getPaymentKey()),eq(840L),anyString(),anyString());
        assertThat(payouts.findAll().getFirst().getAmount()).isEqualTo(8800);
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.COMPLETED);
    }
    @Test void p05CreatesAdditional892And1560() {
        depart(); submit(24000); finalizeFare();
        assertThat(orders.findByPartyIdOrderById(partyId).stream().filter(o->o.getPurpose()==PaymentPurpose.ADDITIONAL).map(PaymentOrder::getAmount).toList()).containsExactly(892L,1560L);
        verify(gateway,never()).cancel(anyString(),anyLong(),anyString(),anyString());
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.SETTLED);
    }
    @Test void p06OnlyUnpaidMemberIsRemovedWithoutNoShow() {
        approve(0); at(NOW.plusSeconds(600)); lifecycle.beforeDepartures();
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(2);
        RideRequest removed=requests.findAll().stream().filter(r->r.getMember().getId().equals(ids.get(1))).findFirst().orElseThrow();
        assertThat(removed.getStatus()).isEqualTo(RequestStatus.CANCELED); assertThat(removed.isNoShow()).isFalse();
        assertThat(order(0).getAmount()).isEqualTo(3680);
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.READY);
    }
    @Test void p07FiveIdenticalRefundsCallGatewayOnce() {
        depart(); submit(16800); finalizeFare();
        for(int i=0;i<5;i++) { payments.refundTo(order(0).getId(),480,"정산 차액"); payments.retryDue(); }
        verify(gateway,times(1)).cancel(eq(order(0).getPaymentKey()),eq(480L),anyString(),anyString());
    }
    @Test void p08ObjectionStopsRefundsAdditionalOrdersAndPayout() {
        depart(); submit(24000); settlementService.objectToFare(ids.get(0),partyId,"영수증 금액 확인");
        at(clock.now().plusSeconds(25*3600)); lifecycle.afterDepartures();
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.REVIEW_PENDING);
        assertThat(payouts.count()).isZero(); assertThat(orders.findAll()).hasSize(2);
        verify(gateway,never()).cancel(anyString(),anyLong(),anyString(),anyString());
    }
    @Test void e04MissingFareRefundsAllPrepaymentsAndClosesSubmission() {
        depart(); at(NOW.plusSeconds(1800+24*3600)); lifecycle.afterDepartures();
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.FARE_MISSING);
        assertThat(order(0).getRefundedAmount()).isEqualTo(3680); assertThat(order(1).getRefundedAmount()).isEqualTo(6440);
        assertThatThrownBy(()->submit(16800)).isInstanceOf(ApiException.class);
    }
    @Test void mismatchedServerAmountIsRejectedBeforeGateway() {
        PaymentOrder o=order(0);
        assertThatThrownBy(()->payments.confirm(ids.get(0),o.getOrderId(),"key",o.getAmount()+1)).isInstanceOf(ApiException.class);
        verify(gateway,never()).confirm(anyString(),anyString(),anyLong(),anyString());
    }
    @Test void sameOrderConfirmationCallsGatewayOnce() {
        approve(0); approve(0); verify(gateway,times(1)).confirm(anyString(),anyString(),eq(3680L),anyString());
    }
    @Test void reservationAndInstantDeadlinesFollowConfirmedSlot() {
        assertThat(PaymentService.prepayDeadline(NOW,NOW.plusSeconds(1800),false)).isEqualTo(NOW.plusSeconds(600));
        assertThat(PaymentService.prepayDeadline(NOW,NOW.plusSeconds(1800),true)).isEqualTo(NOW.plusSeconds(180));
        assertThat(PaymentService.prepayDeadline(NOW,NOW.plusSeconds(1500),false)).isEqualTo(NOW.plusSeconds(300));
        // S39: 전날 확정이어도 기한은 출발 20분 전(예전에는 확정 + 60분)
        assertThat(PaymentService.prepayDeadline(NOW,NOW.plusSeconds(86400),false)).isEqualTo(NOW.plusSeconds(86400-1200));
        assertThat(PaymentService.prepayAmount(1)).isEqualTo(2);
    }
    @Test void correctionRestartsObjectionWindowButStopsAfterObjectionOrSettlement() {
        depart(); submit(16800); Instant initial=settlements.findAll().getFirst().getObjectionDeadlineAt();
        at(clock.now().plusSeconds(3600)); settlementService.correct(ids.get(2),partyId,17000,receipt(),null,null);
        assertThat(settlements.findAll().getFirst().getObjectionDeadlineAt()).isEqualTo(initial.plusSeconds(3600));
        settlementService.objectToFare(ids.get(1),partyId,"금액 확인");
        assertThatThrownBy(()->settlementService.correct(ids.get(2),partyId,16800,receipt(),null,null)).isInstanceOf(ApiException.class);
    }
    @Test void cannotCorrectFinalizedFare() {
        depart(); submit(16800); finalizeFare();
        assertThatThrownBy(()->settlementService.correct(ids.get(2),partyId,17000,receipt(),null,null)).isInstanceOf(ApiException.class);
    }
    @Test void overdueAdditionalBlocksRequestsUntilApproval() {
        depart(); submit(24000); finalizeFare(); at(clock.now().plusSeconds(24*3600)); lifecycle.afterDepartures();
        assertThat(rideRequests.isRestricted(ids.get(0))).isTrue();
        PaymentOrder o=orders.findByPartyIdAndMemberIdAndPurpose(partyId,ids.get(0),PaymentPurpose.ADDITIONAL).orElseThrow();
        payments.confirm(ids.get(0),o.getOrderId(),"extra_"+o.getOrderId(),o.getAmount());
        assertThat(rideRequests.isRestricted(ids.get(0))).isFalse();
    }
    @Test void responseLostAfterCancellationIsRecoveredByLookup() {
        depart(); submit(16800);
        doAnswer(c->{provider.cancel(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3));throw new PaymentGateway.Unavailable("모의 응답 유실");})
                .when(gateway).cancel(anyString(),anyLong(),anyString(),anyString());
        finalizeFare(); payments.retryDue();
        assertThat(order(0).getRefundedAmount()).isEqualTo(480);
        verify(gateway,times(2)).cancel(anyString(),anyLong(),anyString(),anyString());
    }
    @Test void failedOperationsStopAfterThreeRetries() {
        depart(); submit(16800);
        doThrow(new PaymentGateway.Unavailable("모의 장애")).when(gateway).cancel(anyString(),anyLong(),anyString(),anyString());
        finalizeFare();
        for(int i=0;i<3;i++){at(clock.now().plusSeconds(60));payments.retryDue();}
        assertThat(operations.findAll().stream().filter(o->o.getType()==PaymentOperationType.CANCEL)).allMatch(o->o.getAttempts()==4&&o.getStatus()==PaymentOperationStatus.ADMIN_REQUIRED);
        payments.retryDue(); verify(gateway,times(8)).cancel(anyString(),anyLong(),anyString(),anyString());
    }
    @Test void notOperatedDeadlineCannotBecomeLateStart() {
        approve(0);approve(1);at(NOW.plusSeconds(1800+300));slots.runLocked(()->{slots.process(clock.now());return null;});
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.RIDE_UNCONFIRMED);
        assertThat(order(0).getRefundedAmount()).isZero();
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(3);
    }
    @Test void dissolutionRefundsApprovedMember() {
        approve(0);
        rideRequests.cancel(ids.get(2),requests.findAll().stream().filter(r->r.getMember().getId().equals(ids.get(2))).findFirst().orElseThrow().getId());
        rideRequests.cancel(ids.get(1),requests.findAll().stream().filter(r->r.getMember().getId().equals(ids.get(1))).findFirst().orElseThrow().getId());
        payments.retryDue();
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.DISSOLVED);
        assertThat(order(0).getRefundedAmount()).isEqualTo(3680);
    }
    @Test void twelveHourReminderIsSentOnce() {
        depart();at(NOW.plusSeconds(1800+12*3600));lifecycle.afterDepartures();lifecycle.afterDepartures();
        assertThat(jdbc.queryForObject("select count(*) from mail_outbox where subject=?",Integer.class,"[캠퍼스 택시메이트] 정산 금액 입력 안내")).isEqualTo(1);
    }
    @Test void anotherMemberCannotApproveOrder() {
        PaymentOrder o=order(0);
        assertThatThrownBy(()->payments.confirm(ids.get(1),o.getOrderId(),"key",o.getAmount())).isInstanceOf(ApiException.class);
        verify(gateway,never()).confirm(anyString(),anyString(),anyLong(),anyString());
    }

    @Test void p02OneWonResidualGoesToNonPayers() {
        new TransactionTemplate(manager).executeWithoutResult(status->{
            Party party=parties.findByIdForUpdate(partyId).orElseThrow();party.setPayer(members.findById(ids.get(0)).orElseThrow());
            requests.findByPartyOrderByRideOrder(party).forEach(r->r.setRatioUnits(1L));
        });
        assertThat(settlementService.preview(ids.get(0),partyId,10001).stream().map(SettlementService.ShareView::amount).toList())
                .containsExactly(3333L,3334L,3334L);
    }
    @Test void p03ServerPreviewPreservesSumAndPayerBoundForOneThousandAmounts() {
        for(long amount=1;amount<=1000;amount++) {
            var values=settlementService.preview(ids.get(2),partyId,amount);
            assertThat(values.stream().mapToLong(SettlementService.ShareView::amount).sum()).isEqualTo(amount);
            assertThat(values).allMatch(v->v.amount()>=0);
            assertThat(values.get(2).amount()*31500).isLessThanOrEqualTo(amount*15000);
        }
    }
    @Test void promotedPayerKeepsDepositUntilSettlementThenGetsFullRefund() {
        approve(0);approve(1);
        long formerPayerRequest=requests.findAll().stream().filter(r->r.getMember().getId().equals(ids.get(2))).findFirst().orElseThrow().getId();
        rideRequests.cancel(ids.get(2),formerPayerRequest);
        assertThat(parties.findById(partyId).orElseThrow().getPayer().getId()).isEqualTo(ids.get(1));
        assertThat(order(1).getRefundTarget()).isZero();
        at(NOW.plusSeconds(600));lifecycle.beforeDepartures();at(NOW.plusSeconds(1800));slots.runLocked(()->{slots.process(clock.now());return null;});
        settlementService.submit(ids.get(1),partyId,12000,receipt(),null,null);
        finalizeFare();
        assertThat(order(1).getRefundedAmount()).isEqualTo(6440);
        assertThat(payouts.findAll().getFirst().getMemberId()).isEqualTo(ids.get(1));
    }

    @Test void freeCancellationDeadlineUsesLaterOfPrepaymentAndTMinusThirty() {
        approve(0);at(NOW.plusSeconds(9*60));
        Party party=parties.findById(partyId).orElseThrow();
        assertThat(party.freeCancelUntil()).isEqualTo(NOW.plusSeconds(600));
        long request=requests.findAll().stream().filter(r->r.getMember().getId().equals(ids.get(0))).findFirst().orElseThrow().getId();
        rideRequests.cancel(ids.get(0),request);payments.retryDue();
        assertThat(order(0).getRefundedAmount()).isEqualTo(3680);
    }
    @Test void C02_lateCancellationCompensatesAtSettlementWithoutNoShow() {
        approve(0);approve(1);
        new TransactionTemplate(manager).executeWithoutResult(tx->{var p=parties.findByIdForUpdate(partyId).orElseThrow();p.setDepartureAt(NOW.plusSeconds(7200));p.setPrepayDeadlineAt(NOW.plusSeconds(3600));orders.findByPartyIdOrderById(partyId).forEach(o->o.setDeadlineAt(NOW.plusSeconds(3600)));});
        at(NOW.plusSeconds(6000));lifecycle.beforeDepartures();
        long request=requests.findAll().stream().filter(r->r.getMember().getId().equals(ids.get(0))).findFirst().orElseThrow().getId();
        rideRequests.cancel(ids.get(0),request);payments.retryDue();
        assertThat(order(0).getRefundedAmount()).isZero();
        assertThat(requests.findById(request).orElseThrow().isNoShow()).isFalse();
        at(NOW.plusSeconds(7200));slots.runLocked(()->{slots.process(clock.now());return null;});
        submit(20000);finalizeFare();
        var charge=compensationEntries.findByPartyIdOrderByRequestId(partyId).stream().filter(e->e.getMemberId().equals(ids.get(0))).findFirst().orElseThrow();
        assertThat(charge.getCharge()).isEqualTo(3200);assertThat(order(0).getRefundedAmount()).isEqualTo(480);
        assertThat(requests.findById(request).orElseThrow().isNoShow()).isFalse();
    }

    @Test void lostApprovalResponseIsRecoveredWithoutSecondCharge() {
        doAnswer(c->{provider.confirm(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3));throw new PaymentGateway.Unavailable("승인 응답 유실");})
                .when(gateway).confirm(anyString(),anyString(),anyLong(),anyString());
        approve(0);
        assertThat(order(0).getStatus()).isEqualTo(PaymentStatus.APPROVED);
        verify(gateway,times(1)).confirm(anyString(),anyString(),anyLong(),anyString());
        verify(gateway,times(1)).get(eq(order(0).getPaymentKey()));
    }
    @Test void approvalDiscoveredAfterExpiryIsRefundedAndDoesNotRejoinParty() {
        PaymentOrder original=order(0);
        doAnswer(c->{provider.confirm(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3));throw new PaymentGateway.Unavailable("승인 응답 유실");})
                .when(gateway).confirm(anyString(),eq(original.getOrderId()),anyLong(),anyString());
        doThrow(new PaymentGateway.Unavailable("조회도 일시 장애")).when(gateway).get(anyString());
        approve(0);approve(1);
        at(NOW.plusSeconds(1200));lifecycle.beforeDepartures();
        doAnswer(c->provider.get(c.getArgument(0))).when(gateway).get(anyString());
        at(NOW.plusSeconds(1260));payments.retryDue();payments.retryDue();
        assertThat(order(0).getRefundedAmount()).isEqualTo(3680);
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).noneMatch(r->r.getMember().getId().equals(ids.get(0)));
        verify(gateway,times(1)).confirm(anyString(),eq(original.getOrderId()),anyLong(),anyString());
    }
    @Test void concurrentApprovalStillCallsGatewayOnce() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var results=pool.invokeAll(java.util.List.<java.util.concurrent.Callable<Void>>of(
                    ()->{approve(0);return null;},()->{approve(0);return null;}));
            for(var result:results) result.get();
        } finally { pool.shutdownNow(); }
        assertThat(order(0).getStatus()).isEqualTo(PaymentStatus.APPROVED);
        verify(gateway,times(1)).confirm(anyString(),anyString(),anyLong(),anyString());
        assertThat(operations.findAll()).hasSize(1);
    }

    /** 새 흐름은 전원 미표시여도 T에 노쇼로 빼지 않고 운행 확인으로 넘어간다. */
    void unconfirmed() {
        approve(0);approve(1);
        new TransactionTemplate(manager).executeWithoutResult(s->requests.findByPartyOrderByRideOrder(parties.findById(partyId).orElseThrow()).forEach(r->r.setArrived(false)));
        at(NOW.plusSeconds(600));lifecycle.beforeDepartures();
        at(NOW.plusSeconds(1800));slots.runLocked(()->{slots.process(clock.now());return null;});
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(3);
        assertThat(requests.findAll()).allMatch(r->!r.isNoShow()&&r.getNoShowAt()==null);
        at(NOW.plusSeconds(2100));slots.runLocked(()->{slots.process(clock.now());return null;});
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.RIDE_UNCONFIRMED);
    }

    @Test void p09ProcessingAtDeadlineIsRetainedWhenLookupConfirmsApproval() {
        approve(0);PaymentOrder b=order(1);
        doAnswer(c->{provider.confirm(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3));throw new PaymentGateway.Unavailable("승인 응답 유실");})
                .when(gateway).confirm(anyString(),eq(b.getOrderId()),anyLong(),anyString());
        doThrow(new PaymentGateway.Unavailable("기한 전 조회 장애")).when(gateway).get(anyString());
        approve(1);assertThat(order(1).getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        doAnswer(c->provider.get(c.getArgument(0))).when(gateway).get(anyString());
        at(NOW.plusSeconds(600));lifecycle.beforeDepartures();
        assertThat(order(1).getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(order(1).getExcludedAt()).isNull();
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(3);
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.READY);
        verify(gateway,never()).cancel(anyString(),anyLong(),anyString(),anyString());
        verify(gateway,times(1)).confirm(anyString(),eq(b.getOrderId()),anyLong(),anyString());
    }
    @Test void p10UnknownUntilCallIsRemovedAndLateApprovalRefundedExactlyOnce() {
        approve(0);PaymentOrder b=order(1);
        doAnswer(c->{provider.confirm(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3));throw new PaymentGateway.Unavailable("모의 응답 유실");})
                .when(gateway).confirm(anyString(),eq(b.getOrderId()),anyLong(),anyString());
        doThrow(new PaymentGateway.Unavailable("모의 조회 장애")).when(gateway).get(anyString());
        approve(1);
        at(NOW.plusSeconds(600));lifecycle.beforeDepartures();
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(3);
        at(NOW.plusSeconds(1199));lifecycle.beforeDepartures();
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(3);
        at(NOW.plusSeconds(1200));lifecycle.beforeDepartures();
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(2);
        assertThat(order(1).getExcludedAt()).isEqualTo(clock.now());
        doAnswer(c->provider.get(c.getArgument(0))).when(gateway).get(anyString());
        for(int i=0;i<5;i++){lifecycle.beforeDepartures();payments.retryDue();}
        assertThat(order(1).getRefundedAmount()).isEqualTo(6440);
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).noneMatch(r->r.getMember().getId().equals(ids.get(1)));
        verify(gateway,times(1)).cancel(eq(order(1).getPaymentKey()),eq(6440L),anyString(),anyString());
        verify(gateway,times(1)).confirm(anyString(),eq(b.getOrderId()),anyLong(),anyString());
    }
    @Test void terminalPaymentFailureIsRemovedAtPrepayDeadlineWithoutGrace() {
        approve(0);PaymentOrder b=order(1);
        doThrow(new PaymentGateway.Unavailable("확정 실패")).when(gateway).confirm(anyString(),eq(b.getOrderId()),anyLong(),anyString());
        doAnswer(c->new PaymentGateway.Payment(c.getArgument(0),b.getOrderId(),b.getAmount(),0,false,PaymentGateway.Resolution.FAILED)).when(gateway).get(anyString());
        approve(1);at(NOW.plusSeconds(600));lifecycle.beforeDepartures();
        assertThat(order(1).getExcludedAt()).isEqualTo(clock.now());
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(2);
    }
    @Test void c06OriginalTermsSurviveRecalculationAndBusinessVersionBecomesTwo() {
        approve(0);at(NOW.plusSeconds(600));lifecycle.beforeDepartures();
        var a=terms.findByPartyIdAndMemberId(partyId,ids.get(0)).orElseThrow();
        assertThat(a.getConfirmedShare()).isEqualTo(3200);assertThat(a.getConfirmedPrepay()).isEqualTo(3680);
        assertThat(a.isMigratedUnknown()).isFalse();
        assertThat(order(0).getAmount()).isEqualTo(3680);
        assertThat(parties.findById(partyId).orElseThrow().getGroupVersion()).isEqualTo(2);
        assertThat(partyChanges.findByPartyIdOrderByGroupVersion(partyId)).singleElement().satisfies(c->{assertThat(c.getGroupVersion()).isEqualTo(2);assertThat(c.getReason()).isEqualTo("PREPAY_UNPAID");assertThat(c.getChangedAt()).isEqualTo(clock.now());});
        assertThat(partyService.view(ids.get(0),partyId).myConfirmedShare()).isEqualTo(3200);
    }
    @Test void n08PayerRodeStartsFareInputAndInvestigatesUnconfirmedMembers() {
        unconfirmed();rideConfirmations.answer(ids.get(2),partyId,true);
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.AWAITING_FARE);
        assertThat(requests.findByStatus(RequestStatus.COMPLETED)).hasSize(1);
        assertThat(requests.findByStatus(RequestStatus.CANCELED)).hasSize(2);
        assertThat(boardingCases.findByPartyIdOrderByRequestId(partyId)).hasSize(2).allMatch(BoardingCase::pending);
        assertThat(terms.findByPartyIdOrderById(partyId)).allMatch(t->t.getBoardingUnconfirmedAt()!=null);
        assertThat(members.findAll().stream().filter(m->ids.contains(m.getId()))).allMatch(m->m.getNoShowCount()==0);
        assertThat(partyService.view(ids.get(2),partyId).canSettle()).isTrue();
        verify(gateway,never()).cancel(anyString(),anyLong(),anyString(),anyString());
    }
    @Test void n09NoPayerAnswerOrFareByTPlus24RefundsAllAndClosesInput() {
        unconfirmed();at(NOW.plusSeconds(1800+24*3600-1));lifecycle.afterDepartures();
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.RIDE_UNCONFIRMED);
        assertThat(order(0).getRefundedAmount()).isZero();
        at(NOW.plusSeconds(1800+24*3600));lifecycle.afterDepartures();
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.NOT_OPERATED);
        assertThat(order(0).getRefundedAmount()).isEqualTo(3680);assertThat(order(1).getRefundedAmount()).isEqualTo(6440);
        assertThatThrownBy(()->submit(16800)).isInstanceOf(ApiException.class);
    }
    @Test void fareWithinTPlus24IsAcceptedWithoutPayerAnswer() {
        unconfirmed();at(NOW.plusSeconds(1800+23*3600));submit(16800);
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.OBJECTION_WINDOW);
        assertThat(allocations.findAll()).hasSize(1);assertThat(boardingCases.findByPartyIdOrderByRequestId(partyId)).hasSize(2);assertThat(order(0).getRefundedAmount()).isZero();
    }
    @Test void contradictoryAnswersHoldAllRefundsAndStopSettlement() {
        unconfirmed();rideConfirmations.answer(ids.get(0),partyId,true);rideConfirmations.answer(ids.get(2),partyId,false);
        lifecycle.afterDepartures();
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.REVIEW_PENDING);
        assertThat(parties.findById(partyId).orElseThrow().getReviewReason()).isEqualTo("RIDE_DISPUTE");
        assertThat(order(0).getRefundedAmount()).isZero();assertThat(order(1).getRefundedAmount()).isZero();
        assertThatThrownBy(()->submit(16800)).isInstanceOf(ApiException.class);
        at(NOW.plusSeconds(1800+24*3600));lifecycle.afterDepartures();
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.REVIEW_PENDING);
        verify(gateway,never()).cancel(anyString(),anyLong(),anyString(),anyString());
    }
    @Test void payerNoRefundsButLateRodeOnlyRecordsAdminFlag() {
        unconfirmed();rideConfirmations.answer(ids.get(2),partyId,false);payments.retryDue();
        assertThat(order(0).getRefundedAmount()).isEqualTo(3680);assertThat(order(1).getRefundedAmount()).isEqualTo(6440);
        rideConfirmations.answer(ids.get(0),partyId,true);payments.retryDue();
        Party party=parties.findById(partyId).orElseThrow();
        assertThat(party.getStatus()).isEqualTo(PartyStatus.NOT_OPERATED);assertThat(party.isAdminReviewRequired()).isTrue();
        assertThat(party.getReviewReason()).isEqualTo("LATE_RIDE_ANSWER_AFTER_REFUND");
        assertThat(rideAnswers.findByPartyIdAndMemberId(partyId,ids.get(0)).orElseThrow().isRode()).isTrue();
        verify(gateway,times(2)).cancel(anyString(),anyLong(),anyString(),anyString());
    }
    @Test void firstAnswerIsFinalAndAnIdenticalRetryIsIdempotent() {
        unconfirmed();rideConfirmations.answer(ids.get(0),partyId,true);rideConfirmations.answer(ids.get(0),partyId,true);
        assertThatThrownBy(()->rideConfirmations.answer(ids.get(0),partyId,false)).isInstanceOf(ApiException.class);
        assertThat(rideAnswers.findByPartyIdOrderById(partyId)).hasSize(1);
        assertThatThrownBy(()->rideConfirmations.answer(Long.MAX_VALUE,partyId,true)).isInstanceOf(ApiException.class);
    }
    @Test void disagreementBeforeRefundStartsCancelsPendingRefundAndWaitsForReview() {
        unconfirmed();rideConfirmations.answer(ids.get(2),partyId,false);
        rideConfirmations.answer(ids.get(0),partyId,true);payments.retryDue();
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.REVIEW_PENDING);
        assertThat(order(0).getRefundTarget()).isZero();assertThat(order(0).getRefundedAmount()).isZero();
        verify(gateway,never()).cancel(anyString(),anyLong(),anyString(),anyString());
    }

    void changedGroup() { approve(0);at(NOW.plusSeconds(600));lifecycle.beforeDepartures(); }
    @Test void s30BeforeCallChangeExitIsFreeAndRefunded() {
        changedGroup();at(NOW.plusSeconds(1199));
        assertThat(rideRequests.leaveAfterChange(ids.get(0),partyId,2)).isTrue();payments.retryDue();
        assertThat(order(0).getRefundedAmount()).isEqualTo(3680);
        assertThat(payouts.count()).isZero();assertThat(compensationEntries.count()).isZero();
        assertThat(terms.findByPartyIdAndMemberId(partyId,ids.get(0)).orElseThrow().isRefundHeld()).isFalse();
    }
    @Test void s30AtCallChangeExitIsNotFreeEvenIfPartyDissolves() {
        changedGroup();at(NOW.plusSeconds(1200));
        assertThat(rideRequests.leaveAfterChange(ids.get(0),partyId,2)).isFalse();payments.retryDue();
        assertThat(order(0).getRefundedAmount()).isEqualTo(480);assertThat(order(0).getRefundTarget()).isEqualTo(480);
        assertThat(terms.findByPartyIdAndMemberId(partyId,ids.get(0)).orElseThrow().isRefundHeld()).isFalse();
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.DISSOLVED);
        assertThat(payouts.findAll()).singleElement().satisfies(p->{assertThat(p.getMemberId()).isEqualTo(ids.get(2));assertThat(p.getAmount()).isEqualTo(3200);});
    }

    @Test void s30AfterCallAndStaleGroupVersionAreHandledSeparately() {
        changedGroup();at(NOW.plusSeconds(1201));
        assertThatThrownBy(()->rideRequests.leaveAfterChange(ids.get(0),partyId,1)).isInstanceOf(ApiException.class);
        assertThat(parties.findById(partyId).orElseThrow().getGroupVersion()).isEqualTo(2);
        assertThat(rideRequests.leaveAfterChange(ids.get(0),partyId,2)).isFalse();payments.retryDue();
        assertThat(order(0).getRefundedAmount()).isEqualTo(480);
        assertThat(payouts.findAll()).singleElement().satisfies(p->{assertThat(p.getMemberId()).isEqualTo(ids.get(2));assertThat(p.getAmount()).isEqualTo(3200);});
    }

    @Test void deadlineLookupRacingApprovalDoesNotExcludeOrDoubleCharge() throws Exception {
        approve(0);PaymentOrder b=order(1);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(c->{entered.countDown();if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("승인 대기 시간 초과");return provider.confirm(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3));})
                .when(gateway).confirm(anyString(),eq(b.getOrderId()),anyLong(),anyString());
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future=pool.submit(()->approve(1));assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            at(NOW.plusSeconds(600));lifecycle.beforeDepartures();
            assertThat(order(1).getExcludedAt()).isNull();assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(3);
            release.countDown();future.get(10,java.util.concurrent.TimeUnit.SECONDS);lifecycle.beforeDepartures();
            assertThat(order(1).getStatus()).isEqualTo(PaymentStatus.APPROVED);assertThat(order(1).getRefundTarget()).isZero();
            verify(gateway,times(1)).confirm(anyString(),eq(b.getOrderId()),anyLong(),anyString());
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void rideConfirmationApiRequiresMembershipAndKeepsFirstAnswer() throws Exception {
        unconfirmed();var session=new org.springframework.mock.web.MockHttpSession();
        session.setAttribute("memberId",ids.get(2));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/parties/"+partyId+"/ride-confirmation")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"rode\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/parties/"+partyId+"/ride-confirmation")
                .session(session).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"rode\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.myRideAnswer").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.canConfirmRide").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("AWAITING_FARE"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/parties/"+partyId+"/ride-confirmation")
                .session(session).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"rode\":false}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        assertThat(rideAnswers.findByPartyIdOrderById(partyId)).hasSize(1);
    }
    @Test void changedExitApiUsesServerVersionAndRefundPolicy() throws Exception {
        changedGroup();at(NOW.plusSeconds(1200));var session=new org.springframework.mock.web.MockHttpSession();
        session.setAttribute("memberId",ids.get(0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/parties/"+partyId+"/leave-after-change")
                .session(session).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"groupVersion\":1}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/parties/"+partyId+"/leave-after-change")
                .session(session).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"groupVersion\":2}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.freeRefund").value(false));
        assertThat(order(0).getRefundedAmount()).isEqualTo(480);
        assertThat(payouts.findAll()).singleElement().satisfies(p->{assertThat(p.getMemberId()).isEqualTo(ids.get(2));assertThat(p.getAmount()).isEqualTo(3200);});
    }

    @Test void changeExitRechecksCallDeadlineAfterRouteLookup() {
        approve(0);approve(1);at(NOW.plusSeconds(600));lifecycle.beforeDepartures();
        new TransactionTemplate(manager).executeWithoutResult(s->{var p=parties.findByIdForUpdate(partyId).orElseThrow();p.setGroupVersion(2);});
        at(NOW.plusSeconds(1199));
        doAnswer(c->{at(NOW.plusSeconds(1201));return c.callRealMethod();}).when(routeClient)
                .routeAt(any(),any(),any());
        assertThat(rideRequests.leaveAfterChange(ids.get(0),partyId,2)).isFalse();payments.retryDue();
        assertThat(order(0).getRefundedAmount()).isZero();
        assertThat(terms.findByPartyIdAndMemberId(partyId,ids.get(0)).orElseThrow().isRefundHeld()).isTrue();
    }

    @Test void overdueApprovalRacingDeadlineProcessingAlwaysClearsOnlyThatMembersRestriction() throws Exception {
        depart();submit(24000);finalizeFare();
        PaymentOrder extra=orders.findByPartyIdAndMemberIdAndPurpose(partyId,ids.get(0),PaymentPurpose.ADDITIONAL).orElseThrow();
        at(extra.getDeadlineAt());lifecycle.afterDepartures();
        assertThat(rideRequests.isRestricted(ids.get(0))).isTrue();
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(c->{entered.countDown();if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("추가 결제 대기 시간 초과");return provider.confirm(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3));})
                .when(gateway).confirm(anyString(),eq(extra.getOrderId()),anyLong(),anyString());
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future=pool.submit(()->payments.confirm(ids.get(0),extra.getOrderId(),"extra_"+extra.getOrderId(),extra.getAmount()));
            assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            lifecycle.afterDepartures();
            release.countDown();assertThat(future.get(10,java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(rideRequests.isRestricted(ids.get(0))).isFalse();
            for(int i=0;i<5;i++) lifecycle.afterDepartures();
            assertThat(rideRequests.isRestricted(ids.get(0))).isFalse();
            assertThat(rideRequests.isRestricted(ids.get(1))).isTrue();
            verify(gateway,times(1)).confirm(anyString(),eq(extra.getOrderId()),eq(892L),anyString());
        } finally {release.countDown();pool.shutdownNow();}
    }

    void instantParty() {
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            var p=parties.findByIdForUpdate(partyId).orElseThrow();p.setInstant(true);p.setDepartureAt(NOW.plusSeconds(540));p.setPrepayDeadlineAt(NOW.plusSeconds(180));
            orders.findByPartyIdOrderById(partyId).forEach(o->o.setDeadlineAt(NOW.plusSeconds(180)));
            requests.findByPartyOrderByRideOrder(p).forEach(r->r.setArrived(false));
        });
    }
    @Test void i04ReadyRequiresBothPrepaymentsAndEveryMembersArrivalPromise() {
        instantParty();approve(0);
        for(Long id:ids)readiness.prepare(id,partyId,"NEARBY");
        assertThat(partyService.view(ids.get(2),partyId).callReady()).isFalse();
        approve(1);
        assertThat(partyService.view(ids.get(2),partyId).callReady()).isTrue();
        assertThat(partyService.view(ids.get(0),partyId).canCall()).isFalse();
        assertThat(partyService.view(ids.get(2),partyId).canCall()).isTrue();
        assertThatThrownBy(()->readiness.call(ids.get(0),partyId)).isInstanceOf(ApiException.class);
        readiness.call(ids.get(2),partyId);readiness.call(ids.get(2),partyId);
        assertThat(parties.findById(partyId).orElseThrow().getCalledAt()).isEqualTo(NOW);
        assertThat(parties.findById(partyId).orElseThrow().getStartedAt()).isNull();
    }
    @Test void i04OneUnmarkedMemberKeepsCallButtonOffUntilTwoMinutePromise() {
        instantParty();approve(0);approve(1);
        readiness.prepare(ids.get(0),partyId,"ARRIVED");readiness.prepare(ids.get(2),partyId,"NEARBY");
        var before=partyService.view(ids.get(2),partyId);
        assertThat(before.canCall()).isFalse();assertThat(before.callReady()).isFalse();assertThat(before.notReadyReasons()).anyMatch(r->r.contains("2분 안 도착"));
        readiness.prepare(ids.get(1),partyId,"NEARBY");assertThat(partyService.view(ids.get(2),partyId).canCall()).isTrue();
        readiness.prepare(ids.get(0),partyId,"NEARBY");assertThat(partyService.view(ids.get(0),partyId).members()).anyMatch(m->m.memberId()==ids.get(0)&&m.arrived());
    }
    @Test void i05EstimatedTAllowsCallAndRecordsUnconfirmedWithoutNoShow() {
        instantParty();approve(0);approve(1);at(NOW.plusSeconds(180));lifecycle.beforeDepartures();
        at(NOW.plusSeconds(539));assertThat(partyService.view(ids.get(2),partyId).canCall()).isFalse();
        at(NOW.plusSeconds(540));var ready=partyService.view(ids.get(2),partyId);
        assertThat(ready.callReady()).isFalse();assertThat(ready.canCall()).isTrue();
        readiness.call(ids.get(2),partyId);
        assertThat(terms.findByPartyIdOrderById(partyId)).allMatch(t->t.getBoardingUnconfirmedAt()!=null);
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(3);assertThat(requests.findAll()).allMatch(r->!r.isNoShow());
        at(NOW.plusSeconds(840));lifecycle.beforeDepartures();assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.RIDE_UNCONFIRMED);
    }
    @Test void reservationCallStillWaitsForTMinusTenWithoutArrivalPromises() {
        approve(0);approve(1);at(NOW.plusSeconds(600));lifecycle.beforeDepartures();
        at(NOW.plusSeconds(1199));assertThat(partyService.view(ids.get(2),partyId).canCall()).isFalse();
        at(NOW.plusSeconds(1200));assertThat(partyService.view(ids.get(2),partyId).canCall()).isTrue();
    }
    @Test void preparationApisRequireMemberAndServerChecksCallReadiness() throws Exception {
        instantParty();var session=new org.springframework.mock.web.MockHttpSession();session.setAttribute("memberId",ids.get(2));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/parties/"+partyId+"/readiness").session(session)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"state\":\"NEARBY\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.callReady").value(false));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/parties/"+partyId+"/call").session(session))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/parties/"+partyId+"/readiness"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        assertThatThrownBy(()->readiness.prepare(Long.MAX_VALUE,partyId,"ARRIVED")).isInstanceOf(ApiException.class);
    }

    @Test void sevenDayJumpCompletesAllRefundsEvenAtSubMicrosecondTimestamp() {
        depart();submit(16800);
        // datetime(6)이 999ns를 다음 마이크로초로 반올림하면 예전 코드는 이 회차의 즉시 환불을 놓쳤다.
        at(NOW.plusSeconds(1800+7*24*3600L).plusNanos(999));lifecycle.afterDepartures();
        assertThat(order(0).getRefundedAmount()).isEqualTo(480);assertThat(order(1).getRefundedAmount()).isEqualTo(840);
        assertThat(operations.findAll()).allMatch(op->op.getStatus()==PaymentOperationStatus.SUCCEEDED);
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.COMPLETED);
        assertThat(payouts.findAll()).hasSize(1);
    }
    @Test void oneRetryPassAlsoExecutesRefundCreatedByLateApprovalLookup() {
        PaymentOrder a=order(0);approve(1);
        doAnswer(c->{provider.confirm(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3));throw new PaymentGateway.Unavailable("승인 응답 유실");})
                .when(gateway).confirm(anyString(),eq(a.getOrderId()),anyLong(),anyString());
        doThrow(new PaymentGateway.Unavailable("조회 장애")).when(gateway).get(anyString());
        approve(0);at(NOW.plusSeconds(1200));lifecycle.beforeDepartures();
        doAnswer(c->provider.get(c.getArgument(0))).when(gateway).get(anyString());
        payments.retryDue();
        assertThat(order(0).getRefundedAmount()).isEqualTo(3680);
        assertThat(operations.findByPaymentOrderIdOrderById(order(0).getId())).allMatch(op->op.getStatus()==PaymentOperationStatus.SUCCEEDED);
        verify(gateway,times(1)).cancel(eq(order(0).getPaymentKey()),eq(3680L),anyString(),anyString());
    }

    @Test void instantPendingPaymentWaitsUntilEstimatedTThenExcludesWithoutDepartureConflict() {
        instantParty();approve(0);var b=order(1);
        doThrow(new PaymentGateway.Unavailable("모의 확인 중")).when(gateway).confirm(anyString(),eq(b.getOrderId()),anyLong(),anyString());
        approve(1);at(NOW.plusSeconds(180));lifecycle.beforeDepartures();
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(3);
        at(NOW.plusSeconds(540));lifecycle.beforeDepartures();
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(2);
        assertThat(order(1).getExcludedAt()).isEqualTo(clock.now());
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.READY);
        assertThat(partyService.view(ids.get(2),partyId).canCall()).isTrue();
    }

    long adminId(){return members.findByEmail("demo-admin@daejin.ac.kr").orElseThrow().getId();}
    RideRequest rider(int index){return requests.findAll().stream().filter(r->r.getMember().getId().equals(ids.get(index))).findFirst().orElseThrow();}
    BoardingCase investigation(int index){return boardingCases.findByPartyIdAndRequestId(partyId,rider(index).getId()).orElseThrow();}
    void departWithout(int index){
        approve(0);approve(1);at(NOW.plusSeconds(600));lifecycle.beforeDepartures();
        new TransactionTemplate(manager).executeWithoutResult(tx->requests.findById(rider(index).getId()).orElseThrow().setArrived(false));
        at(NOW.plusSeconds(1800));
        new TransactionTemplate(manager).executeWithoutResult(tx->rideConfirmations.start(parties.findByIdForUpdate(partyId).orElseThrow(),clock.now()));
    }
    void reviewB(){departWithout(1);submit(15500);at(investigation(1).getDeadlineAt());lifecycle.afterDepartures();assertThat(investigation(1).getStatus()).isEqualTo("REVIEW");}
    CompensationEntry comp(int index){return compensationEntries.findByPartyIdOrderByRequestId(partyId).stream().filter(e->e.getMemberId().equals(ids.get(index))).findFirst().orElseThrow();}

    @Test void N01_confirmedBNoShowCharges4300Refunds2140AndPayerReceives7500(){
        departWithout(1);noShows.vote(ids.get(0),partyId,ids.get(1),false);submit(15500);
        assertThat(boardingCases.findAll()).singleElement().satisfies(c->assertThat(c.getStatus()).isEqualTo("PENDING"));
        assertThat(order(1).getRefundedAmount()).isZero();assertThat(payouts.count()).isZero();
        finalizeFare();
        assertThat(comp(1).getCharge()).isEqualTo(4300);assertThat(order(1).getRefundedAmount()).isEqualTo(2140);
        assertThat(comp(0).getNetShare()).isEqualTo(3200);assertThat(comp(2).getNetShare()).isEqualTo(8000);
        assertThat(payouts.findAll()).singleElement().satisfies(p->assertThat(p.getAmount()).isEqualTo(7500));
        assertThat(rider(1).isNoShow()).isTrue();assertThat(members.findById(ids.get(1)).orElseThrow().getNoShowCount()).isEqualTo(1);
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.COMPLETED);
    }
    @Test void N02_driverRefusalIsNotNoShowAndRefundsEveryDeposit(){
        approve(0);approve(1);
        new TransactionTemplate(manager).executeWithoutResult(tx->rideConfirmations.notOperated(parties.findByIdForUpdate(partyId).orElseThrow()));
        payments.retryDue();
        assertThat(order(0).getRefundedAmount()).isEqualTo(3680);assertThat(order(1).getRefundedAmount()).isEqualTo(6440);
        assertThat(boardingCases.count()).isZero();assertThat(requests.findAll()).noneMatch(RideRequest::isNoShow);
    }
    @Test void N03_acceptedExplanationRefundsAllBAndMakesNoNoShowRecord(){
        departWithout(1);noShows.vote(ids.get(0),partyId,ids.get(1),false);
        noShows.explain(ids.get(1),investigation(1).getId(),"기사님이 승차를 거절했어요.");submit(15500);finalizeFare();
        assertThat(investigation(1).getStatus()).isEqualTo("REVIEW");assertThat(payouts.count()).isZero();
        admin.decideNoShow(adminId(),investigation(1).getId(),"NOT_NO_SHOW","기사 거절 소명 확인");lifecycle.afterDepartures();
        assertThat(order(1).getRefundedAmount()).isEqualTo(6440);assertThat(comp(1).getCharge()).isZero();assertThat(rider(1).isNoShow()).isFalse();
        assertThat(members.findById(ids.get(1)).orElseThrow().getNoShowCount()).isZero();
    }
    @Test void N04_conflictingPeerVotesRequireReviewAndHoldAllSettlementEffects(){
        departWithout(1);noShows.vote(ids.get(0),partyId,ids.get(1),false);noShows.vote(ids.get(2),partyId,ids.get(1),true);
        submit(24000);finalizeFare();
        assertThat(investigation(1).getStatus()).isEqualTo("REVIEW");assertThat(payouts.count()).isZero();assertThat(compensationEntries.count()).isZero();
        assertThat(orders.findAll()).hasSize(2);verify(gateway,never()).cancel(anyString(),anyLong(),anyString(),anyString());
    }
    @Test void N05_onlyTwoConfirmedNoShowsWithinThirtyDaysCauseSevenDayRestriction(){
        // 지난 일행의 확정 노쇼 한 건과 이번 조사 중 한 건을 구분한다.
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            var template=rider(1);RideRequest old=new RideRequest();old.setMember(members.findById(ids.get(1)).orElseThrow());
            old.setDirection(Direction.FROM_HUB);old.setHub(hubs.findAll().getFirst());old.setPointName("지난 도착지");old.setRequestedAt(NOW.minusSeconds(86400));old.setStatus(RequestStatus.CANCELED);old.setNoShow(true);old.setNoShowAt(NOW.minusSeconds(86400));requests.save(old);
            var m=members.findById(ids.get(1)).orElseThrow();m.setNoShowCount(1);
        });
        // rider() 대신 현재 일행 요청을 지정해 과거 기록과 섞이지 않게 한다.
        approve(0);approve(1);at(NOW.plusSeconds(600));lifecycle.beforeDepartures();
        new TransactionTemplate(manager).executeWithoutResult(tx->{var p=parties.findByIdForUpdate(partyId).orElseThrow();requests.findByPartyOrderByRideOrder(p).stream().filter(r->r.getMember().getId().equals(ids.get(1))).forEach(r->r.setArrived(false));});
        at(NOW.plusSeconds(1800));new TransactionTemplate(manager).executeWithoutResult(tx->rideConfirmations.start(parties.findByIdForUpdate(partyId).orElseThrow(),clock.now()));
        var c=boardingCases.findByPartyIdOrderByRequestId(partyId).getFirst();noShows.vote(ids.get(0),partyId,ids.get(1),false);
        assertThat(memberRestrictions.findAll()).isEmpty();at(c.getDeadlineAt());noShows.adjudicate(c.getId());
        assertThat(memberRestrictions.findAll()).singleElement().satisfies(r->{assertThat(r.getReason()).isEqualTo("NO_SHOW_REPEATED");assertThat(r.getExpiresAt()).isEqualTo(clock.now().plusSeconds(7*86400));});
        noShows.adjudicate(c.getId());assertThat(members.findById(ids.get(1)).orElseThrow().getNoShowCount()).isEqualTo(2);
    }
    void absentPayerOffer(){
        approve(0);approve(1);at(NOW.plusSeconds(600));lifecycle.beforeDepartures();
        new TransactionTemplate(manager).executeWithoutResult(tx->requests.findById(rider(2).getId()).orElseThrow().setArrived(false));
        at(NOW.plusSeconds(1800));noShows.vote(ids.get(0),partyId,ids.get(2),false);
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.PAYER_PENDING);
    }
    @Test void N06_payerSuccessionKeepsDepositUntilFinalizationAndHasNoCompensationFromOriginalPayer(){
        absentPayerOffer();noShows.succession(ids.get(1),partyId,true);
        assertThat(parties.findById(partyId).orElseThrow().getPayer().getId()).isEqualTo(ids.get(1));assertThat(order(1).getRefundedAmount()).isZero();
        settlementService.submit(ids.get(1),partyId,12000,receipt(),null,null);finalizeFare();
        assertThat(order(1).getRefundedAmount()).isEqualTo(6440);assertThat(comp(2).getCharge()).isZero();assertThat(rider(2).isNoShow()).isTrue();
        assertThat(investigation(2).isNoCompensation()).isTrue();
    }
    @Test void N07_compensationCap5600Splits12800IncreaseByItsRatio(){
        departWithout(1);noShows.vote(ids.get(0),partyId,ids.get(1),false);submit(24000);finalizeFare();
        assertThat(comp(1).getCharge()).isEqualTo(5600);assertThat(comp(0).getCredit()).isEqualTo(1600);assertThat(comp(2).getCredit()).isEqualTo(4000);
        assertThat(order(1).getRefundedAmount()).isEqualTo(840);
    }
    @Test void N10_refusedSuccessionDoesNotOfferNextCandidateAndRefundsOthers(){
        absentPayerOffer();noShows.succession(ids.get(1),partyId,false);payments.retryDue();
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.NOT_OPERATED);
        assertThat(order(0).getRefundedAmount()).isEqualTo(3680);assertThat(order(1).getRefundedAmount()).isEqualTo(6440);
        assertThatThrownBy(()->noShows.succession(ids.get(0),partyId,true)).isInstanceOf(ApiException.class);
        at(investigation(2).getDeadlineAt());lifecycle.afterDepartures();assertThat(rider(2).isNoShow()).isTrue();
        assertThat(compensationEntries.count()).isZero();assertThat(payouts.count()).isZero();
    }
    @Test void N10_unansweredSuccessionExpiresWithoutAnotherOffer(){
        absentPayerOffer();at(NOW.plusSeconds(2100));lifecycle.beforeDepartures();payments.retryDue();
        assertThat(parties.findById(partyId).orElseThrow().getSuccessionStatus()).isEqualTo("REFUSED");
        assertThat(order(0).getRefundedAmount()).isEqualTo(3680);assertThat(order(1).getRefundedAmount()).isEqualTo(6440);
    }
    @Test void N11_twoAbsenteesShareTheSameCompensationTotalWithinBothCaps(){
        long fourth=members.findByEmail("demo-f4@daejin.ac.kr").orElseThrow().getId();
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            var p=parties.findByIdForUpdate(partyId).orElseThrow();RideRequest r=new RideRequest();r.setMember(members.getReferenceById(fourth));r.setDirection(Direction.FROM_HUB);r.setHub(p.getHub());r.setPointName("추가 도착지");r.setRequestedAt(NOW);r.setParty(p);r.setRideOrder(2);r.setRatioUnits(9000L);r.setSoloFare(9000);r.setExpectedShare(4000L);r.setStatus(RequestStatus.MATCHED);r.setArrived(false);requests.save(r);
            payments.createObligation(partyId,fourth,r.getId(),null,PaymentPurpose.PREPAY,4600,p.getPrepayDeadlineAt());
            changeService.capture(p,List.of(r),false);
        });
        var extra=orders.findByPartyIdAndMemberIdAndPurpose(partyId,fourth,PaymentPurpose.PREPAY).orElseThrow();payments.confirm(fourth,extra.getOrderId(),"fourth",extra.getAmount());
        departWithout(1);noShows.vote(ids.get(0),partyId,ids.get(1),false);noShows.vote(ids.get(2),partyId,fourth,false);submit(24000);finalizeFare();
        var values=compensationEntries.findByPartyIdOrderByRequestId(partyId);
        assertThat(values.stream().mapToLong(CompensationEntry::getCharge).sum()).isEqualTo(values.stream().mapToLong(CompensationEntry::getCredit).sum()).isEqualTo(9600);
        assertThat(comp(1).getCharge()).isEqualTo(5600);assertThat(values.stream().filter(e->e.getMemberId().equals(fourth)).findFirst().orElseThrow().getCharge()).isEqualTo(4000);
    }
    @Test void N12_unresolvableConflictMeansNotNoShowAndFullRefund(){
        departWithout(1);noShows.vote(ids.get(0),partyId,ids.get(1),false);noShows.vote(ids.get(2),partyId,ids.get(1),true);submit(15500);finalizeFare();
        admin.decideNoShow(adminId(),investigation(1).getId(),"UNDETERMINED","추가 증거가 없어 판단 불가");lifecycle.afterDepartures();
        assertThat(investigation(1).getStatus()).isEqualTo("EXONERATED");assertThat(order(1).getRefundedAmount()).isEqualTo(6440);assertThat(rider(1).isNoShow()).isFalse();
    }
    @Test void lateChangedExitUsesSameSettlementCompensation(){
        approve(0);approve(1);at(NOW.plusSeconds(600));lifecycle.beforeDepartures();
        new TransactionTemplate(manager).executeWithoutResult(tx->parties.findByIdForUpdate(partyId).orElseThrow().setGroupVersion(2));
        at(NOW.plusSeconds(1201));assertThat(rideRequests.leaveAfterChange(ids.get(1),partyId,2)).isFalse();
        assertThat(investigation(1).getKind()).isEqualTo("LATE_CANCEL");assertThat(investigation(1).getStatus()).isEqualTo("CONFIRMED");
        at(NOW.plusSeconds(1800));slots.runLocked(()->{slots.process(clock.now());return null;});submit(15500);finalizeFare();
        assertThat(comp(1).getCharge()).isEqualTo(4300);assertThat(order(1).getRefundedAmount()).isEqualTo(2140);assertThat(rider(1).isNoShow()).isFalse();
    }
    @Test void A01_adminPathsRejectNormalMemberAndAllowAdmin() throws Exception {
        var user=new org.springframework.mock.web.MockHttpSession();user.setAttribute("memberId",ids.get(0));
        var administrator=new org.springframework.mock.web.MockHttpSession();administrator.setAttribute("memberId",adminId());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/no-shows").session(user)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/no-shows").session(administrator)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThatThrownBy(()->admin.noShowReviews(ids.get(0))).isInstanceOf(ApiException.class);
    }
    @Test void adminDecisionIsIdempotentAndAutomaticallySettlesAfterInvestigation() throws Exception {
        reviewB();var session=new org.springframework.mock.web.MockHttpSession();session.setAttribute("memberId",adminId());long id=investigation(1).getId();
        String json="{\"decision\":\"NO_SHOW\",\"evidence\":\"탑승 확인 자료 검토\"}";
        for(int i=0;i<2;i++) mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/no-shows/"+id+"/decision").session(session).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(adminAudits.count()).isEqualTo(1);assertThat(order(1).getRefundedAmount()).isEqualTo(2140);assertThat(payouts.count()).isEqualTo(1);
        assertThat(members.findById(ids.get(1)).orElseThrow().getNoShowCount()).isEqualTo(1);
        verify(gateway,times(1)).cancel(eq(order(1).getPaymentKey()),eq(2140L),anyString(),anyString());
    }
    @Test void adminFareRejectionResumesSettlementAndCannotBeAppliedTwice(){
        depart();submit(16800);settlementService.objectToFare(ids.get(0),partyId,"확인 요청");
        at(NOW.plusSeconds(1800+86400));admin.decideFare(adminId(),partyId,false,null,"영수증 원금과 일치");lifecycle.afterDepartures();
        admin.decideFare(adminId(),partyId,false,null,"영수증 원금과 일치");lifecycle.afterDepartures();
        assertThat(payouts.count()).isEqualTo(1);assertThat(adminAudits.count()).isEqualTo(1);assertThat(order(0).getRefundedAmount()).isEqualTo(480);
    }
    @Test void explanationDeadlineAndPeerPermissionsAreStrict(){
        departWithout(1);long id=investigation(1).getId();
        assertThatThrownBy(()->noShows.vote(ids.get(1),partyId,ids.get(1),false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->noShows.explain(ids.get(0),id,"대신 소명")).isInstanceOf(ApiException.class);
        at(investigation(1).getDeadlineAt());assertThatThrownBy(()->noShows.explain(ids.get(1),id,"기한 지난 소명")).isInstanceOf(ApiException.class);
        noShows.adjudicate(id);assertThat(investigation(1).getStatus()).isEqualTo("REVIEW");
    }
    @Test void C05_unpaidBThenAChangeExitAfterCallIsNotFreeAndKeepsOriginalCap(){
        changedGroup();at(NOW.plusSeconds(1201));
        assertThat(rideRequests.leaveAfterChange(ids.get(0),partyId,2)).isFalse();payments.retryDue();
        assertThat(investigation(0).getKind()).isEqualTo("LATE_CANCEL");assertThat(investigation(0).getStatus()).isEqualTo("CONFIRMED");
        assertThat(terms.findByPartyIdAndMemberId(partyId,ids.get(0)).orElseThrow().getConfirmedShare()).isEqualTo(3200);
        assertThat(compensationEntries.count()).isEqualTo(2);assertThat(comp(0).getCharge()).isEqualTo(3200);assertThat(comp(2).getCredit()).isEqualTo(3200);assertThat(order(0).getRefundedAmount()).isEqualTo(480);assertThat(rider(0).isNoShow()).isFalse();
        assertThat(payouts.findAll()).singleElement().satisfies(p->{assertThat(p.getMemberId()).isEqualTo(ids.get(2));assertThat(p.getAmount()).isEqualTo(3200);});
    }

    @Test void adminFareAcceptanceRecalculatesActualSharesAndWritesOneAudit(){
        depart();submit(24000);settlementService.objectToFare(ids.get(0),partyId,"실제 금액은 영수증의 16800원");
        admin.decideFare(adminId(),partyId,true,16800L,"영수증 금액 확인");lifecycle.afterDepartures();
        assertThat(settlements.findAll().getFirst().getPaidAmount()).isEqualTo(16800);
        assertThat(order(0).getRefundedAmount()).isEqualTo(480);assertThat(order(1).getRefundedAmount()).isEqualTo(840);
        assertThat(adminAudits.findAll()).singleElement().satisfies(a->{assertThat(a.getActorId()).isEqualTo(adminId());assertThat(a.getEvidence()).isEqualTo("영수증 금액 확인");});
    }
    @Test void adminRideDisputeWithoutReceiptMeansNotOperatedAndFullRefund(){
        unconfirmed();rideConfirmations.answer(ids.get(0),partyId,true);rideConfirmations.answer(ids.get(2),partyId,false);
        admin.decideRide(adminId(),partyId,"영수증이 없어 운행 미성사로 판정");lifecycle.afterDepartures();
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.NOT_OPERATED);
        assertThat(order(0).getRefundedAmount()).isEqualTo(3680);assertThat(order(1).getRefundedAmount()).isEqualTo(6440);
    }
    @Test void adminRideDisputeWithReceiptResumesFareSettlement(){
        depart();submit(16800);
        new TransactionTemplate(manager).executeWithoutResult(tx->parties.findByIdForUpdate(partyId).orElseThrow().setRideAskedAt(clock.now()));
        rideConfirmations.answer(ids.get(0),partyId,false);rideConfirmations.answer(ids.get(2),partyId,true);
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.REVIEW_PENDING);
        admin.decideRide(adminId(),partyId,"결제자 영수증으로 운행 확인");finalizeFare();
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.COMPLETED);assertThat(payouts.count()).isEqualTo(1);
    }
    @Test void adminReviewListOmitsChatAndDetailContainsOnlyTheInvestigatedParty() throws Exception {
        reviewB();long id=investigation(1).getId();
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            var p=parties.findByIdForUpdate(partyId).orElseThrow();jdbc.update("insert into party_message(party_id,member_id,text,created_at) values(?,?,?,?)",partyId,ids.get(0),"해당 일행 자료",java.sql.Timestamp.from(clock.now()));
            var other=parties.save(new Party(Direction.FROM_HUB,p.getHub(),null,members.getReferenceById(ids.get(0)),NOW,10000));
            jdbc.update("insert into party_message(party_id,member_id,text,created_at) values(?,?,?,?)",other.getId(),ids.get(0),"다른 일행 비공개 자료",java.sql.Timestamp.from(clock.now()));
        });
        assertThat(admin.noShowDetail(adminId(),id).chat()).singleElement().satisfies(m->assertThat(m.text()).isEqualTo("해당 일행 자료"));
        var session=new org.springframework.mock.web.MockHttpSession();session.setAttribute("memberId",adminId());
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/no-shows").session(session)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(response).doesNotContain("해당 일행 자료","다른 일행 비공개 자료","chat");
    }
    @Test void adminRestrictionGrantReleaseAndHubVisibilityAreAuditedAndIdempotent(){
        var restriction=admin.restrict(adminId(),ids.get(0),NOW.plusSeconds(86400),"신고 내용 확인","restriction-one");
        admin.restrict(adminId(),ids.get(0),NOW.plusSeconds(86400),"신고 내용 확인","restriction-one");
        assertThat(memberRestrictions.count()).isEqualTo(1);assertThat(rideRequests.isRestricted(ids.get(0))).isTrue();
        admin.release(adminId(),restriction.getId(),"제한 해제 확인");assertThat(rideRequests.isRestricted(ids.get(0))).isFalse();
        long school=members.findWithSchoolById(ids.get(0)).orElseThrow().getSchool().getId();
        var input=new AdminService.HubInput("새 승차 지점","정차 메모",37.8,127.1,Set.of(school),true,false);
        var hub=admin.saveHub(adminId(),null,input,"현장 확인 대기","hub-one");admin.saveHub(adminId(),null,input,"현장 확인 대기","hub-one");
        assertThat(hubs.findBySchool(school)).noneMatch(h->h.getId().equals(hub.id()));
        admin.saveHub(adminId(),hub.id(),new AdminService.HubInput(input.name(),input.meetingPoint(),input.lat(),input.lon(),input.schoolIds(),true,true),"현장 확인 완료","hub-two");
        assertThat(hubs.findBySchool(school)).anyMatch(h->h.getId().equals(hub.id()));
        admin.saveHub(adminId(),hub.id(),new AdminService.HubInput(input.name(),input.meetingPoint(),input.lat(),input.lon(),input.schoolIds(),false,true),"운영 중단","hub-three");
        assertThat(hubs.findBySchool(school)).noneMatch(h->h.getId().equals(hub.id()));assertThat(adminAudits.count()).isEqualTo(5);
    }
    @Test void adminOverdueHandledFlagDoesNotForgePaymentApprovalOrRemoveDebtRestriction(){
        depart();submit(24000);finalizeFare();var extra=orders.findByPartyIdAndMemberIdAndPurpose(partyId,ids.get(0),PaymentPurpose.ADDITIONAL).orElseThrow();
        at(extra.getDeadlineAt());lifecycle.afterDepartures();
        admin.handleOverdue(adminId(),extra.getId(),"추가 결제 안내 처리");admin.handleOverdue(adminId(),extra.getId(),"추가 결제 안내 처리");
        var result=orders.findById(extra.getId()).orElseThrow();assertThat(result.getStatus()).isEqualTo(PaymentStatus.OVERDUE);assertThat(result.getAdminHandledBy()).isEqualTo(adminId());
        assertThat(rideRequests.isRestricted(ids.get(0))).isTrue();assertThat(adminAudits.count()).isEqualTo(1);
    }
    @Test void concurrentAdminDecisionsAndAutomaticSettlementProduceOneCompensationAndRefund() throws Exception {
        reviewB();long id=investigation(1).getId();long administrator=adminId();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var tasks=List.<java.util.concurrent.Callable<Void>>of(
                ()->{admin.decideNoShow(administrator,id,"NO_SHOW","탑승 자료 검토");lifecycle.afterDepartures();return null;},
                ()->{admin.decideNoShow(administrator,id,"NO_SHOW","탑승 자료 검토");lifecycle.afterDepartures();return null;},
                ()->{lifecycle.afterDepartures();return null;});
            for(var result:pool.invokeAll(tasks)) result.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        assertThat(adminAudits.count()).isEqualTo(1);assertThat(compensationEntries.findByPartyIdOrderByRequestId(partyId)).hasSize(3);
        assertThat(payouts.count()).isEqualTo(1);assertThat(order(1).getRefundedAmount()).isEqualTo(2140);
        verify(gateway,times(1)).cancel(eq(order(1).getPaymentKey()),eq(2140L),anyString(),anyString());
    }
    @Test void automaticNoShowAndSameAdminDecisionCountOnlyOnce(){
        departWithout(1);noShows.vote(ids.get(0),partyId,ids.get(1),false);submit(15500);finalizeFare();
        assertThat(investigation(1).getStatus()).isEqualTo("CONFIRMED");
        admin.decideNoShow(adminId(),investigation(1).getId(),"NO_SHOW","자동 판정 자료 확인");lifecycle.afterDepartures();
        admin.decideNoShow(adminId(),investigation(1).getId(),"NO_SHOW","자동 판정 자료 확인");lifecycle.afterDepartures();
        assertThat(members.findById(ids.get(1)).orElseThrow().getNoShowCount()).isEqualTo(1);assertThat(adminAudits.count()).isEqualTo(1);assertThat(payouts.count()).isEqualTo(1);
        verify(gateway,times(1)).cancel(eq(order(1).getPaymentKey()),eq(2140L),anyString(),anyString());
    }
    @Test void ownInvestigationsRemainDiscoverableAfterRemovalAndAdminReceiptRequiresRole() throws Exception {
        departWithout(1);submit(15500);
        var user=new org.springframework.mock.web.MockHttpSession();user.setAttribute("memberId",ids.get(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/boarding-cases").session(user))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].investigation.memberId").value(ids.get(1)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].investigation.partyId").value(partyId));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/parties/"+partyId+"/receipt").session(user))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        var administrator=new org.springframework.mock.web.MockHttpSession();administrator.setAttribute("memberId",adminId());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/parties/"+partyId+"/receipt").session(administrator))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("image/png"));
    }
    @Test void N02_rideDisputePreventsPrematureNoShowAndDriverRefusalRefundsWithoutRecord(){
        departWithout(1);
        new TransactionTemplate(manager).executeWithoutResult(tx->parties.findByIdForUpdate(partyId).orElseThrow().setRideAskedAt(clock.now()));
        rideConfirmations.answer(ids.get(0),partyId,false);rideConfirmations.answer(ids.get(2),partyId,true);
        at(investigation(1).getDeadlineAt());lifecycle.afterDepartures();
        assertThat(investigation(1).getStatus()).isEqualTo("PENDING");assertThat(rider(1).isNoShow()).isFalse();
        admin.decideRide(adminId(),partyId,"기사 거절로 운행하지 못했고 영수증 없음");lifecycle.afterDepartures();
        assertThat(investigation(1).getStatus()).isEqualTo("EXONERATED");assertThat(rider(1).isNoShow()).isFalse();
        assertThat(order(0).getRefundedAmount()).isEqualTo(3680);assertThat(order(1).getRefundedAmount()).isEqualTo(6440);
        assertThat(payments.get(ids.get(1),order(1).getOrderId()).refundHeld()).isFalse();
    }

    /** N-13: 확정 때 A 8000/B 5600인 두 사람. 변경 이탈용 버전은 이전 변경을 마친 상태다. */
    void prepareN13(long survivorSolo) {
        PaymentTestSupport.clear(jdbc);transfers.deleteAll();settlements.deleteAll();requests.deleteAll();parties.deleteAll();
        at(NOW);
        partyId=new TransactionTemplate(manager).execute(tx->{
            Party p=parties.save(new Party(Direction.FROM_HUB,hubs.findAll().getFirst(),null,members.getReferenceById(ids.get(2)),NOW.plusSeconds(7200),13600));
            p.setGroupVersion(2);
            List<RideRequest> pair=new ArrayList<>();
            for(int member:new int[]{1,2}) {
                RideRequest r=new RideRequest();r.setMember(members.getReferenceById(ids.get(member)));r.setActiveMemberId(ids.get(member));
                r.setDirection(Direction.FROM_HUB);r.setHub(p.getHub());r.setPointName(member==2?"A 도착지":"B 도착지");
                r.setPointLat(37.7-member*0.01);r.setPointLon(127.1);r.setRequestedAt(NOW);r.setParty(p);r.setRideOrder(member);
                r.setWindowStart(p.getDepartureAt());r.setWindowEnd(p.getDepartureAt().plusSeconds(1800));
                r.setSoloFare(member==2?survivorSolo:10500);r.setRatioUnits(r.getSoloFare());r.setSoloKm(10);r.setSoloSeconds(1200);
                r.setExpectedShare(member==2?8000L:5600L);r.setStatus(RequestStatus.MATCHED);r.setArrived(true);pair.add(requests.save(r));
            }
            payments.preparePrepayments(p,pair,NOW);return p.getId();
        });
    }
    void assertN13(long charge,long refund) {
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.DISSOLVED);
        assertThat(order(1).getRefundedAmount()).isEqualTo(refund);assertThat(comp(1).getCharge()).isEqualTo(charge);assertThat(comp(2).getCredit()).isEqualTo(charge);
        assertThat(payouts.findAll()).singleElement().satisfies(p->{assertThat(p.getMemberId()).isEqualTo(ids.get(2));assertThat(p.getAmount()).isEqualTo(charge);assertThat(p.getSettlementId()).isNull();});
        assertThat(settlements.count()).isZero();assertThat(requests.findAll()).noneMatch(RideRequest::isNoShow);
        assertThat(members.findAll().stream().filter(m->ids.contains(m.getId()))).allMatch(m->m.getNoShowCount()==0);
    }
    @Test void N13_changeExitAfterCallCharges5600Refunds840AndPaysSurvivor5600() {
        prepareN13(15000);approve(1);at(NOW.plusSeconds(6601));
        assertThat(rideRequests.leaveAfterChange(ids.get(1),partyId,2)).isFalse();payments.retryDue();
        assertN13(5600,840);
        assertThat(orders.findByPartyIdAndMemberIdAndPurpose(partyId,ids.get(2),PaymentPurpose.PREPAY)).isEmpty();
        assertThat(terms.findByPartyIdAndMemberId(partyId,ids.get(2)).orElseThrow().getConfirmedShare()).isEqualTo(8000);
        verify(gateway,times(1)).cancel(eq(order(1).getPaymentKey()),eq(840L),anyString(),anyString());
    }
    @Test void N13_generalLateCancellationUsesSameDissolutionCompensation() {
        prepareN13(15000);approve(1);at(NOW.plusSeconds(6060)) /* S39: 무료 취소 마감(T−20분=6000초) 1분 뒤 */;
        rideRequests.cancel(ids.get(1),rider(1).getId());payments.retryDue();assertN13(5600,840);
    }
    @Test void N13_increaseBelowCapCharges2000AndRefunds4440() {
        prepareN13(10000);approve(1);at(NOW.plusSeconds(6601));
        assertThat(rideRequests.leaveAfterChange(ids.get(1),partyId,2)).isFalse();payments.retryDue();assertN13(2000,4440);
    }
    @Test void N13_freeChangeExitBeforeCallStillRefundsAllWithoutCompensation() {
        prepareN13(15000);approve(1);at(NOW.plusSeconds(6599));
        assertThat(rideRequests.leaveAfterChange(ids.get(1),partyId,2)).isTrue();payments.retryDue();
        assertThat(order(1).getRefundedAmount()).isEqualTo(6440);assertThat(compensationEntries.count()).isZero();assertThat(payouts.count()).isZero();
        assertThat(requests.findAll()).noneMatch(RideRequest::isNoShow);
    }
    @Test void N13_repeatedDissolutionRefundCreatesOneJournalAndOneGatewayCancel() {
        prepareN13(15000);approve(1);at(NOW.plusSeconds(6601));
        rideRequests.leaveAfterChange(ids.get(1),partyId,2);
        for(int i=0;i<5;i++) {
            payments.refundForDissolution(partyId,Set.of(rider(1).getId()));payments.refundForDissolution(partyId);payments.retryDue();
            lifecycle.beforeDepartures();lifecycle.afterDepartures();
        }
        assertN13(5600,840);assertThat(compensationEntries.count()).isEqualTo(2);
        verify(gateway,times(1)).cancel(eq(order(1).getPaymentKey()),eq(840L),anyString(),anyString());
    }
    @Test void N13_departingPayerAlsoBearsCompensationAndSurvivorDepositIsFullyRefunded() {
        prepareN13(15000);approve(1);at(NOW.plusSeconds(6060)) /* S39: 무료 취소 마감(T−20분=6000초) 1분 뒤 */;
        rideRequests.cancel(ids.get(2),rider(2).getId());payments.retryDue();
        assertThat(comp(2).getCharge()).isEqualTo(4900);assertThat(comp(1).getCredit()).isEqualTo(4900);
        assertThat(order(1).getRefundedAmount()).isEqualTo(6440);
        assertThat(payouts.findAll()).singleElement().satisfies(p->{assertThat(p.getMemberId()).isEqualTo(ids.get(1));assertThat(p.getAmount()).isEqualTo(4900);});
        PaymentOrder extra=orders.findByPartyIdAndMemberIdAndPurpose(partyId,ids.get(2),PaymentPurpose.ADDITIONAL).orElseThrow();
        assertThat(extra.getAmount()).isEqualTo(4900);assertThat(extra.getDeadlineAt()).isEqualTo(clock.now().plusSeconds(86400));
        payments.confirm(ids.get(2),extra.getOrderId(),"payer_compensation",extra.getAmount());payments.retryDue();
        assertThat(orders.findById(extra.getId()).orElseThrow().getRefundedAmount()).isZero();
        assertThat(requests.findAll()).noneMatch(RideRequest::isNoShow);
    }
    @Test void N13_lateApprovalUsesConfirmedChargeInsteadOfFullRefund() {
        prepareN13(15000);PaymentOrder b=order(1);
        doAnswer(c->{provider.confirm(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3));throw new PaymentGateway.Unavailable("승인 응답 유실");})
                .when(gateway).confirm(anyString(),eq(b.getOrderId()),anyLong(),anyString());
        doThrow(new PaymentGateway.Unavailable("상태 조회 장애")).when(gateway).get(anyString());
        approve(1);assertThat(order(1).getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        at(NOW.plusSeconds(6060)) /* S39: 무료 취소 마감(T−20분=6000초) 1분 뒤 */;rideRequests.cancel(ids.get(1),rider(1).getId());
        doAnswer(c->provider.get(c.getArgument(0))).when(gateway).get(anyString());payments.retryDue();payments.retryDue();
        assertN13(5600,840);verify(gateway,times(1)).cancel(eq(order(1).getPaymentKey()),eq(840L),anyString(),anyString());
    }
    @Test void N13_changeExitRechecksCallDeadlineEvenWhenDissolutionClearsRefundHold() {
        prepareN13(15000);approve(1);at(NOW.plusSeconds(6599));
        doAnswer(c->{at(NOW.plusSeconds(6601));return c.callRealMethod();})
            // 트랜잭션 프록시를 거치지 않게 스파이 본체에 스텁한다(MANDATORY 전파라 프록시로 부르면 실제 메서드가 실행된다).
            .when(org.springframework.test.util.AopTestUtils.<PartyChangeService>getUltimateTargetObject(changeService)).changed(any(Party.class),anySet(),anyString(),anySet());
        assertThat(rideRequests.leaveAfterChange(ids.get(1),partyId,2)).isFalse();payments.retryDue();
        assertN13(5600,840);assertThat(terms.findByPartyIdAndMemberId(partyId,ids.get(1)).orElseThrow().isRefundHeld()).isFalse();
        assertThat(terms.findByPartyIdAndMemberId(partyId,ids.get(1)).orElseThrow().getExitReason()).isEqualTo("LATE_CANCEL");
    }

    long waitingJoiner(long memberId,double km){
        return new TransactionTemplate(manager).execute(tx->{
            var p=parties.findById(partyId).orElseThrow();RideRequest r=new RideRequest();r.setMember(members.getReferenceById(memberId));r.setActiveMemberId(memberId);
            r.setDirection(Direction.FROM_HUB);r.setHub(p.getHub());r.setPointName("합류 도착지");r.setPointLat(37.76);r.setPointLon(127.1);
            r.setRequestedAt(NOW);r.setWindowStart(p.getDepartureAt());r.setWindowEnd(p.getDepartureAt().plusSeconds(1800));r.setSoloFare(9000);r.setSoloKm(km);r.setSoloSeconds(1200);
            return requests.save(r).getId();
        });
    }
    long fourthMember(){return members.findByEmail("demo-f4@daejin.ac.kr").orElseThrow().getId();}
    void joinRoutes(long total){
        com.campustaximate.server.route.RouteClient target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(routeClient);
        doAnswer(c->{List<com.campustaximate.matching.GeoPoint> stops=c.getArgument(1);return new com.campustaximate.server.route.RouteClient.Route(20,stops.size()==1?1200:stops.size()*150,
            stops.size()==1?10000:total,List.of(),stops.size()==1?List.of(1200):Collections.nCopies(stops.size(),150));}).when(target).routeAt(any(),anyList(),nullable(Instant.class));
        doAnswer(c->{Map<String,com.campustaximate.matching.GeoPoint> points=c.getArgument(1);Map<String,com.campustaximate.matching.RoadLeg> result=new HashMap<>();points.keySet().forEach(id->result.put(id,new com.campustaximate.matching.RoadLeg(1,100)));return result;}).when(target).legs(any(),anyMap());
    }
    void approveJoin(PartyJoin join){var order=orders.findById(join.getPaymentOrderId()).orElseThrow();payments.confirm(join.getMemberId(),order.getOrderId(),"join_"+order.getOrderId(),order.getAmount());}
    @Test void J01_joinPaymentAtomicallyAddsMemberWithoutIncreasingOldSharesOrChangingPayer(){
        joinRoutes(15000);long member=fourthMember(),request=waitingJoiner(member,12);var before=partyService.view(ids.get(2),partyId);
        PartyJoin join=joins.offer(member,partyId,request);
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(3);assertThat(requests.findById(request).orElseThrow().getStatus()).isEqualTo(RequestStatus.JOINING);
        assertThat(partyService.view(ids.get(2),partyId).myShare()).isEqualTo(before.myShare());assertThat(parties.findById(partyId).orElseThrow().getGroupVersion()).isEqualTo(1);
        assertThat(join.getDeadlineAt()).isEqualTo(NOW.plusSeconds(300));approveJoin(join);
        var after=partyService.view(ids.get(2),partyId);assertThat(after.members()).hasSize(4);assertThat(after.payerId()).isEqualTo(before.payerId());assertThat(after.departureAt()).isEqualTo(before.departureAt());assertThat(after.groupVersion()).isEqualTo(2);
        for(var old:before.members())assertThat(after.members().stream().filter(m->m.memberId()==old.memberId()).findFirst().orElseThrow().expectedShare()).isLessThanOrEqualTo(old.expectedShare());
        assertThat(order(0).getAmount()).isEqualTo(3680);assertThat(order(1).getAmount()).isEqualTo(6440);
        assertThat(terms.findByPartyIdAndMemberId(partyId,ids.get(0)).orElseThrow().getConfirmedShare()).isEqualTo(3200);
        assertThat(partyJoins.findById(join.getId()).orElseThrow().getState()).isEqualTo("CONFIRMED");
    }
    @Test void J02_joinWithHigherShareForAnExistingMemberIsRejected(){
        joinRoutes(35000);long member=fourthMember(),request=waitingJoiner(member,12);
        assertThatThrownBy(()->joins.offer(member,partyId,request)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("JOIN_SHARE_INCREASE"));
        assertThat(partyJoins.count()).isZero();assertThat(orders.count()).isEqualTo(2);assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(3);
    }
    @Test void J03_joinerCannotBecomeNewLastDropoffPayer(){
        joinRoutes(15000);long member=fourthMember(),request=waitingJoiner(member,30);
        assertThatThrownBy(()->joins.offer(member,partyId,request)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("JOIN_PAYER_CHANGED"));assertThat(partyJoins.count()).isZero();
    }
    @Test void J04_reservationJoinClosesAtTMinusLead(){
        joinRoutes(15000);long member=fourthMember(),request=waitingJoiner(member,12);at(NOW.plusSeconds(300));
        assertThatThrownBy(()->joins.offer(member,partyId,request)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("JOIN_CLOSED"));assertThat(partyJoins.count()).isZero();
    }
    @Test void J04_instantJoinClosesAtConfirmationPlusPreparation(){
        joinRoutes(15000);long member=fourthMember(),request=waitingJoiner(member,12);
        new TransactionTemplate(manager).executeWithoutResult(s->parties.findByIdForUpdate(partyId).orElseThrow().setInstant(true));at(NOW.plusSeconds(180));
        assertThatThrownBy(()->joins.offer(member,partyId,request)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getCode()).isEqualTo("JOIN_CLOSED"));
    }
    @Test void J05_unpaidJoinExpiresAfterFiveMinutesWithoutChangingOriginalParty(){
        joinRoutes(15000);long member=fourthMember(),request=waitingJoiner(member,12);var before=partyService.view(ids.get(2),partyId);PartyJoin join=joins.offer(member,partyId,request);
        at(join.getDeadlineAt());joins.expireDue();payments.retryDue();var after=partyService.view(ids.get(2),partyId);
        assertThat(after.members()).isEqualTo(before.members());assertThat(after.expectedTotal()).isEqualTo(before.expectedTotal());assertThat(after.payerId()).isEqualTo(before.payerId());assertThat(after.departureAt()).isEqualTo(before.departureAt());assertThat(after.groupVersion()).isEqualTo(1);
        assertThat(requests.findById(request).orElseThrow().getStatus()).isEqualTo(RequestStatus.CANCELED);assertThat(partyJoins.findById(join.getId()).orElseThrow().getState()).isEqualTo("EXPIRED");assertThat(parties.findById(partyId).orElseThrow().getPendingJoinId()).isNull();
        assertThat(order(0).getAmount()).isEqualTo(3680);assertThat(order(1).getAmount()).isEqualTo(6440);verify(gateway,never()).cancel(anyString(),anyLong(),anyString(),anyString());
    }
    @Test void J06_twoConcurrentJoinersReserveOnlyOneRemainingSeat()throws Exception{
        joinRoutes(15000);long first=fourthMember();long second=new TransactionTemplate(manager).execute(tx->{
            var m=members.findByEmail("join-second@daejin.ac.kr").orElseGet(()->new Member(members.findById(first).orElseThrow().getSchool(),"join-second@daejin.ac.kr"));m.setNickname("합류 테스트");m.setGender(com.campustaximate.matching.Gender.FEMALE);return members.save(m).getId();});
        long a=waitingJoiner(first,12),b=waitingJoiner(second,13);var barrier=new java.util.concurrent.CyclicBarrier(2);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var results=pool.invokeAll(List.<java.util.concurrent.Callable<Boolean>>of(()->{barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);try{joins.offer(first,partyId,a);return true;}catch(ApiException e){assertThat(e.getCode()).isIn("JOIN_RESERVED","JOIN_CHANGED","JOIN_FULL");return false;}},()->{barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);try{joins.offer(second,partyId,b);return true;}catch(ApiException e){assertThat(e.getCode()).isIn("JOIN_RESERVED","JOIN_CHANGED","JOIN_FULL");return false;}}),20,java.util.concurrent.TimeUnit.SECONDS);
            int successes=0;for(var result:results)if(result.get(10,java.util.concurrent.TimeUnit.SECONDS))successes++;assertThat(successes).isEqualTo(1);
        }finally{pool.shutdownNow();}
        assertThat(partyJoins.findByStateOrderById("PENDING")).hasSize(1);assertThat(requests.findByStatus(RequestStatus.JOINING)).hasSize(1);assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(3);
        approveJoin(partyJoins.findByStateOrderById("PENDING").getFirst());assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(4);assertThat(parties.findById(partyId).orElseThrow().getGroupVersion()).isEqualTo(2);
    }
    @Test void J01_instantJoinWaitsForPaymentBeforeEarlyCallAndKeepsEstimatedT(){
        joinRoutes(15000);long member=fourthMember(),request=waitingJoiner(member,12);approve(0);approve(1);
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            var p=parties.findByIdForUpdate(partyId).orElseThrow();p.setInstant(true);p.setDepartureAt(NOW.plusSeconds(540));
            requests.findByPartyOrderByRideOrder(p).forEach(r->r.setMode(RequestMode.INSTANT));requests.findById(request).orElseThrow().setMode(RequestMode.INSTANT);
        });
        at(NOW.plusSeconds(60));PartyJoin join=joins.offer(member,partyId,request);
        assertThat(partyService.view(ids.get(2),partyId).canCall()).isFalse();assertThat(partyService.view(ids.get(2),partyId).notReadyReasons()).anyMatch(s->s.contains("합류 선결제"));
        assertThatThrownBy(()->readiness.call(ids.get(2),partyId)).isInstanceOf(ApiException.class);
        approveJoin(join);assertThat(parties.findById(partyId).orElseThrow().getDepartureAt()).isEqualTo(NOW.plusSeconds(540));
        assertThat(parties.findById(partyId).orElseThrow().getGroupVersion()).isEqualTo(2);
    }
    @Test void J05_lateApprovalAfterJoinExpiryRefundsAllWithoutChangingOriginalMembers(){
        joinRoutes(15000);long member=fourthMember(),request=waitingJoiner(member,12);PartyJoin join=joins.offer(member,partyId,request);
        var pending=orders.findById(join.getPaymentOrderId()).orElseThrow();
        doAnswer(c->{provider.confirm(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3));throw new PaymentGateway.Unavailable("승인 응답 유실");})
            .when(gateway).confirm(anyString(),eq(pending.getOrderId()),anyLong(),anyString());
        doThrow(new PaymentGateway.Unavailable("조회 시간 초과")).when(gateway).get(anyString());approveJoin(join);
        at(join.getDeadlineAt());joins.expireDue();doAnswer(c->provider.get(c.getArgument(0))).when(gateway).get(anyString());payments.retryDue();payments.retryDue();
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(3);assertThat(parties.findById(partyId).orElseThrow().getGroupVersion()).isEqualTo(1);
        assertThat(orders.findById(pending.getId()).orElseThrow().getRefundedAmount()).isEqualTo(pending.getAmount());
        verify(gateway,times(1)).cancel(eq(orders.findById(pending.getId()).orElseThrow().getPaymentKey()),eq(pending.getAmount()),anyString(),anyString());
    }
    @Test void J01_roundAutomaticallyOffersJoinButAddsNoMemberBeforeApproval(){
        joinRoutes(15000);long member=fourthMember(),request=waitingJoiner(member,12);at(NOW.plusSeconds(60));
        slots.runLocked(()->{slots.process(clock.now());return null;});
        assertThat(partyJoins.findByStateOrderById("PENDING")).singleElement().satisfies(j->{assertThat(j.getMemberId()).isEqualTo(member);assertThat(j.getRequestId()).isEqualTo(request);});
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(3);assertThat(requests.findByStatus(RequestStatus.JOINING)).hasSize(1);
        approveJoin(partyJoins.findByStateOrderById("PENDING").getFirst());assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(4);
    }
}
