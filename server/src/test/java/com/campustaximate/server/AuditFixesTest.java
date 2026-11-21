package com.campustaximate.server;

import static org.assertj.core.api.Assertions.*;

import com.campustaximate.matching.Direction;
import com.campustaximate.matching.Gender;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import com.campustaximate.server.service.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 코드 감사에서 발견된 백엔드 생명주기·정산·노쇼·축소·격리 결함 회귀 테스트. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:audit-fixes;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.rate-limit.enabled=false",
        "app.kakao.rest-key=",
        "app.toss.secret-key=",
        "app.mail.enabled=false",
        "app.upload-dir=target/audit-fixes-uploads"
})
@ActiveProfiles("demo")
@Import(AuditFixesTest.FixedTime.class)
class AuditFixesTest {

    static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean @Primary AppClock fixedAuditClock() {
            return new AppClock() {
                final AtomicReference<Instant> time = new AtomicReference<>(NOW);
                @Override public Instant now() { return time.get(); }
                @Override public void moveTo(Instant target) { time.set(target); }
            };
        }
    }

    @Autowired AppClock clock;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired SchoolRepository schools;
    @Autowired HubRepository hubs;
    @Autowired MemberRepository members;
    @Autowired PartyRepository parties;
    @Autowired RideRequestRepository requests;
    @Autowired PaymentOrderRepository orders;
    @Autowired SettlementRepository settlements;
    @Autowired TransferRequestRepository transfers;
    @Autowired MemberRestrictionRepository restrictions;
    @Autowired PartyService partyService;
    @Autowired PaymentService payments;
    @Autowired PaymentLifecycleService lifecycle;
    @Autowired RideConfirmationService rideConfirmations;
    @Autowired AdminService admin;
    @Autowired PartyShrinker shrinker;
    @Autowired PartyCanceler canceler;
    @Autowired PartyJoinService joins;
    @Autowired BoardService board;
    @Autowired MemberSafetyService safety;
    @Autowired DemoService demo;

    long adminId;
    List<Long> riders;

    @BeforeEach
    void reset() {
        PaymentTestSupport.clear(jdbc);
        jdbc.update("delete from member_report");
        jdbc.update("delete from member_avoid");
        jdbc.update("delete from party_assignment");
        jdbc.update("delete from ride_review");
        jdbc.update("delete from party_message");
        transfers.deleteAll();
        settlements.deleteAll();
        requests.deleteAll();
        parties.deleteAll();
        restrictions.deleteAll();
        jdbc.update("update member set no_show_count=0");
        clock.moveTo(NOW);
        adminId = members.findByEmail("demo-admin@daejin.ac.kr").orElseThrow().getId();
        riders = List.of("demo-f1@daejin.ac.kr", "demo-f2@daejin.ac.kr", "demo-f3@daejin.ac.kr")
                .stream().map(e -> members.findByEmail(e).orElseThrow().getId()).toList();
    }

    private long createPrepaidReadyParty() {
        long partyId = new TransactionTemplate(manager).execute(tx -> {
            Hub hub = hubs.findAll().stream().filter(Hub::isActive).findFirst().orElseThrow();
            Member m0 = members.findById(riders.get(0)).orElseThrow();
            Member m1 = members.findById(riders.get(1)).orElseThrow();
            Member m2 = members.findById(riders.get(2)).orElseThrow();
            Instant dep = NOW.plusSeconds(1800);
            Party p = parties.save(new Party(Direction.FROM_HUB, hub, null, m2, dep, 16800));
            List<RideRequest> list = new ArrayList<>();
            long[] soloFares = {6000L, 10500L, 15000L};
            long[] expectedShares = {3200L, 5600L, 8000L};
            List<Member> ms = List.of(m0, m1, m2);
            for (int i = 0; i < 3; i++) {
                RideRequest r = new RideRequest();
                r.setMember(ms.get(i));
                r.setActiveMemberId(ms.get(i).getId());
                r.setDirection(Direction.FROM_HUB);
                r.setHub(hub);
                r.setMode(RequestMode.RESERVATION);
                r.setTimeBasis(TimeBasis.DEPART);
                r.setWindowStart(dep);
                r.setWindowEnd(dep.plusSeconds(1800));
                r.setRequestedAt(NOW.minusSeconds(60));
                r.setOriginLat(hub.getLat());
                r.setOriginLon(hub.getLon());
                r.setPointName("지점" + i);
                r.setPointLat(hub.getLat() + 0.01 * (i + 1));
                r.setPointLon(hub.getLon());
                r.setSoloKm(3.0 * (i + 1));
                r.setSoloSeconds(600);
                r.setSharedSeconds(720);
                r.setSoloFare(soloFares[i]);
                r.setRatioUnits(soloFares[i]);
                r.setExpectedShare(expectedShares[i]);
                r.setStatus(RequestStatus.MATCHED);
                r.setParty(p);
                r.setRideOrder(i + 1);
                list.add(requests.save(r));
            }
            safety.remember(p, list);
            payments.preparePrepayments(p, list, NOW);
            return p.getId();
        });
        for (PaymentOrder o : orders.findByPartyIdOrderById(partyId)) {
            payments.confirm(o.getMemberId(), o.getOrderId(), "sim_" + o.getOrderId(), o.getAmount());
        }
        new TransactionTemplate(manager).executeWithoutResult(tx ->
                parties.findByIdForUpdate(partyId).orElseThrow().setStatus(PartyStatus.READY));
        return partyId;
    }

    @Test
    void decideRideReleasesRideReviewHeldAndRefundsPrepaymentsOnNonOperation() {
        long partyId = createPrepaidReadyParty();
        clock.moveTo(NOW.plusSeconds(2100));
        lifecycle.beforeDepartures();
        // 결제자가 미운행 응답 -> NOT_OPERATED + 환불 예약(미실행), 이어서 동승자가 탑승 응답 -> pauseUnstartedRefunds(rideReviewHeld=true)
        rideConfirmations.answer(riders.get(2), partyId, false);
        rideConfirmations.answer(riders.get(0), partyId, true);
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.REVIEW_PENDING);
        assertThat(orders.findByPartyIdOrderById(partyId)).allMatch(PaymentOrder::isRideReviewHeld);

        // 관리자가 NOT_OPERATED 판정 시 resolveRideDecision이 보류를 풀고 실제 환불을 완료해야 한다.
        admin.decideRide(adminId, partyId, "NOT_OPERATED", "증언 대조 결과 미운행 확정");
        lifecycle.afterDepartures();

        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.NOT_OPERATED);
        assertThat(orders.findByPartyIdOrderById(partyId))
                .allSatisfy(o -> {
                    assertThat(o.isRideReviewHeld()).isFalse();
                    assertThat(o.getRefundedAmount()).isEqualTo(o.getAmount());
                });
    }

    @Test
    void lateRideAnswerAfterAdminOperatedRecoveryDoesNotBrickParty() {
        long partyId = createPrepaidReadyParty();
        clock.moveTo(NOW.plusSeconds(2100));
        lifecycle.beforeDepartures();
        // RIDE_UNCONFIRMED 상태에서 startedAt==null이면 결제자도 canSettle==false여야 한다.
        assertThat(partyService.view(riders.get(2), partyId).canSettle()).isFalse();

        rideConfirmations.answer(riders.get(2), partyId, false);
        rideConfirmations.answer(riders.get(0), partyId, true);
        admin.decideRide(adminId, partyId, "OPERATED", "실제 탑승 확인");
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.AWAITING_FARE);

        // 미응답 동승자(riders[1])가 뒤늦게 답변해도 이미 확정된 관리자 운행 판정을 덮어쓰지 않는다.
        assertThatThrownBy(() -> rideConfirmations.answer(riders.get(1), partyId, false))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("RIDE_DECISION_FINAL"));
        assertThat(parties.findById(partyId).orElseThrow().getStatus()).isEqualTo(PartyStatus.AWAITING_FARE);
    }

    @Test
    void formerPayerCannotRejoinSamePartyAndCreateDuplicateTerm() {
        long partyId = createPrepaidReadyParty();
        // 결제자(riders[2])가 무료 취소로 빠짐 -> 새 요청으로 같은 일행 재합류 시도
        long payerReqId = requests.findByPartyOrderByRideOrder(parties.findById(partyId).orElseThrow())
                .stream().filter(r -> r.getMember().getId().equals(riders.get(2))).findFirst().orElseThrow().getId();
        shrinker.remove(partyId, Set.of(payerReqId), NOW, false, "FREE_CANCEL", null, Set.of());
        long newReqId = new TransactionTemplate(manager).execute(tx -> {
            Party p = parties.findById(partyId).orElseThrow();
            RideRequest r = new RideRequest();
            r.setMember(members.findById(riders.get(2)).orElseThrow());
            r.setActiveMemberId(riders.get(2));
            r.setDirection(Direction.FROM_HUB);
            r.setHub(p.getHub());
            r.setMode(RequestMode.RESERVATION);
            r.setTimeBasis(TimeBasis.DEPART);
            r.setWindowStart(p.getDepartureAt());
            r.setWindowEnd(p.getDepartureAt().plusSeconds(1800));
            r.setRequestedAt(NOW);
            r.setOriginLat(p.getHub().getLat());
            r.setOriginLon(p.getHub().getLon());
            r.setPointName("재합류 지점");
            r.setPointLat(p.getHub().getLat() + 0.02);
            r.setPointLon(p.getHub().getLon());
            r.setSoloKm(5.0);
            r.setSoloSeconds(600);
            r.setSoloFare(12000L);
            return requests.save(r).getId();
        });
        assertThatThrownBy(() -> joins.offer(riders.get(2), partyId, newReqId))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("JOIN_ALREADY_ATTEMPTED"));
    }

    @Test
    void stalePendingExitOnClosedPartyDoesNotPoisonBeforeDepartures() {
        long partyId = createPrepaidReadyParty();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            Party p = parties.findByIdForUpdate(partyId).orElseThrow();
            RideRequest first = requests.findByPartyOrderByRideOrder(p).getFirst();
            first.setPendingExitReason("CHANGE_EXIT_FREE");
            first.setExitRequestedAt(NOW);
            p.setRouteVerificationPending(true);
            p.setStatus(PartyStatus.NOT_OPERATED);
            first.finish(RequestStatus.CANCELED);
        });
        assertThatCode(() -> lifecycle.beforeDepartures()).doesNotThrowAnyException();
        assertThat(requests.findByPendingExitReasonIsNotNull()).isEmpty();
        assertThat(parties.findById(partyId).orElseThrow().isRouteVerificationPending()).isFalse();
    }

    @Test
    void reportAccumulationExtendsShorterExistingRestrictionToSevenDays() {
        // 짧은 1시간 제한이 이미 걸린 상태에서 서로 다른 3개 일행에서 신고 누적 시 7일 제한이 추가되어야 한다.
        long target = riders.get(2);
        long reporter = riders.get(0);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            MemberRestriction shortRest = new MemberRestriction();
            shortRest.setMemberId(target);
            shortRest.setReason("MANUAL");
            shortRest.setCreatedAt(NOW);
            shortRest.setExpiresAt(NOW.plusSeconds(3600));
            restrictions.save(shortRest);
        });
        for (int i = 0; i < 3; i++) {
            long pid = new TransactionTemplate(manager).execute(tx -> {
                Hub hub = hubs.findAll().stream().filter(Hub::isActive).findFirst().orElseThrow();
                Member m0 = members.findById(reporter).orElseThrow();
                Member m2 = members.findById(target).orElseThrow();
                Party p = parties.save(new Party(Direction.FROM_HUB, hub, null, m2, NOW.plusSeconds(1800), 10000));
                PartyAssignment a1 = new PartyAssignment(); a1.setParty(p); a1.setMemberId(m0.getId()); a1.setRequestId(9000L + p.getId() * 10);
                PartyAssignment a2 = new PartyAssignment(); a2.setParty(p); a2.setMemberId(m2.getId()); a2.setRequestId(9001L + p.getId() * 10);
                jdbc.update("insert into party_assignment(party_id, member_id, request_id) values(?,?,?)", p.getId(), m0.getId(), a1.getRequestId());
                jdbc.update("insert into party_assignment(party_id, member_id, request_id) values(?,?,?)", p.getId(), m2.getId(), a2.getRequestId());
                return p.getId();
            });
            safety.report(reporter, target, pid, ReportReason.RUDE, "반복 신고 " + i);
        }
        var active = safety.activeRestriction(target);
        assertThat(active).isNotNull();
        assertThat(active.reason()).isEqualTo("REPORT_ACCUMULATION");
        assertThat(active.expiresAt()).isEqualTo(NOW.plus(ReportPolicy.RESTRICTION_DURATION));
    }

    @Test
    void boardIsolatesOtherSchoolsAndInstantSurvivorUsesOneMinuteCutoff() {
        long otherSchoolMember = new TransactionTemplate(manager).execute(tx -> {
            School other = schools.findByEmailDomain("other.ac.kr")
                    .orElseGet(() -> schools.save(new School("다른대학교", "other.ac.kr", "다른대 정문", 37.5, 127.0)));
            Member m = new Member(other, "other-" + System.nanoTime() + "@other.ac.kr");
            m.setNickname("타교생");
            m.setGender(Gender.MALE);
            return members.save(m).getId();
        });
        long daejinMale = members.findByEmail("demo-m4@daejin.ac.kr").orElseThrow().getId();
        demo.openBoardParty(daejinMale);
        assertThat(board.of(daejinMale).parties()).hasSize(1);
        assertThat(board.of(otherSchoolMember).parties()).isEmpty();

        // INSTANT 생존자는 5분 컷오프가 아니라 1분 뒤 회차 기준으로 WAITING 복귀 여부를 판정한다.
        long instantPartyId = new TransactionTemplate(manager).execute(tx -> {
            Hub hub = hubs.findAll().stream().filter(Hub::isActive).findFirst().orElseThrow();
            Member m0 = members.findById(riders.get(0)).orElseThrow();
            Member m1 = members.findById(riders.get(1)).orElseThrow();
            Party p = parties.save(new Party(Direction.FROM_HUB, hub, null, m1, NOW.plusSeconds(600), 10000));
            p.setInstant(true);
            for (int i = 0; i < 2; i++) {
                RideRequest r = new RideRequest();
                r.setMember(i == 0 ? m0 : m1);
                r.setActiveMemberId(i == 0 ? m0.getId() : m1.getId());
                r.setDirection(Direction.FROM_HUB);
                r.setHub(hub);
                r.setMode(RequestMode.INSTANT);
                r.setTimeBasis(TimeBasis.DEPART);
                r.setRequestedAt(NOW.minusSeconds(30));
                r.setDeadlineSlot(NOW.plusSeconds(270)); // +4분 30초: 1분 뒤 회차는 유효, 5분 뒤 회차는 초과
                r.setWindowStart(NOW);
                r.setWindowEnd(NOW.plusSeconds(600));
                r.setOriginLat(hub.getLat());
                r.setOriginLon(hub.getLon());
                r.setPointName("즉시" + i);
                r.setPointLat(hub.getLat() + 0.01);
                r.setPointLon(hub.getLon());
                r.setSoloKm(3.0);
                r.setSoloSeconds(600);
                r.setSoloFare(6000L);
                r.setExpectedShare(4500L);
                r.setStatus(RequestStatus.MATCHED);
                r.setParty(p);
                r.setRideOrder(i + 1);
                requests.save(r);
            }
            return p.getId();
        });
        List<RideRequest> pair = requests.findByPartyOrderByRideOrder(parties.findById(instantPartyId).orElseThrow());
        new TransactionTemplate(manager).executeWithoutResult(tx ->
                canceler.cancel(parties.findByIdForUpdate(instantPartyId).orElseThrow(), Set.of(pair.get(0).getId()), NOW.plusSeconds(60)));
        assertThat(requests.findById(pair.get(1).getId()).orElseThrow().getStatus()).isEqualTo(RequestStatus.WAITING);
    }
}
