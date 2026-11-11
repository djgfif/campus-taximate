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
        assertThat(PaymentService.prepayDeadline(NOW,NOW.plusSeconds(1800),true)).isEqualTo(NOW.plusSeconds(300));
        assertThat(PaymentService.prepayDeadline(NOW,NOW.plusSeconds(1500),false)).isEqualTo(NOW.plusSeconds(300));
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
    @Test void lateCancellationKeepsExistingNoCompensationPolicyUntilSettlement() {
        approve(0);approve(1);at(NOW.plusSeconds(11*60));lifecycle.beforeDepartures();
        long request=requests.findAll().stream().filter(r->r.getMember().getId().equals(ids.get(0))).findFirst().orElseThrow().getId();
        rideRequests.cancel(ids.get(0),request);payments.retryDue();
        assertThat(order(0).getRefundedAmount()).isZero();
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
    @Test void n08PayerRodeStartsFareInputAndOnlyRecordsUnconfirmedBoarding() {
        unconfirmed();rideConfirmations.answer(ids.get(2),partyId,true);
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.AWAITING_FARE);
        assertThat(requests.findByStatus(RequestStatus.COMPLETED)).hasSize(3);
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
        assertThat(allocations.findAll()).hasSize(3);assertThat(order(0).getRefundedAmount()).isZero();
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
        assertThat(terms.findByPartyIdAndMemberId(partyId,ids.get(0)).orElseThrow().isRefundHeld()).isFalse();
    }
    @Test void s30AtCallChangeExitIsNotFreeEvenIfPartyDissolves() {
        changedGroup();at(NOW.plusSeconds(1200));
        assertThat(rideRequests.leaveAfterChange(ids.get(0),partyId,2)).isFalse();payments.retryDue();
        assertThat(order(0).getRefundedAmount()).isZero();assertThat(order(0).getRefundTarget()).isZero();
        assertThat(terms.findByPartyIdAndMemberId(partyId,ids.get(0)).orElseThrow().isRefundHeld()).isTrue();
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.DISSOLVED);
    }
    @Test void s30AfterCallAndStaleGroupVersionAreHandledSeparately() {
        changedGroup();at(NOW.plusSeconds(1201));
        assertThatThrownBy(()->rideRequests.leaveAfterChange(ids.get(0),partyId,1)).isInstanceOf(ApiException.class);
        assertThat(parties.findById(partyId).orElseThrow().getGroupVersion()).isEqualTo(2);
        assertThat(rideRequests.leaveAfterChange(ids.get(0),partyId,2)).isFalse();payments.retryDue();
        assertThat(order(0).getRefundedAmount()).isZero();
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
        assertThat(order(0).getRefundedAmount()).isZero();
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
}
