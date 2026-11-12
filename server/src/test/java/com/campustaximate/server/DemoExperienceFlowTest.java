package com.campustaximate.server;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.campustaximate.matching.Direction;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import com.campustaximate.server.service.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:demo-experience;MODE=MySQL;DB_CLOSE_DELAY=-1","app.rate-limit.enabled=false","app.kakao.rest-key=","app.toss.secret-key=","app.mail.enabled=false","app.upload-dir=target/demo-experience-uploads"})
@ActiveProfiles("demo") @AutoConfigureMockMvc
@Import(DemoExperienceFlowTest.FixedTime.class)
class DemoExperienceFlowTest {
    static final Instant NOW=Instant.parse("2026-11-12T00:00:00Z");
    @TestConfiguration(proxyBeanMethods=false)
    static class FixedTime {
        @Bean @Primary AppClock fixedDemoClock() {
            return new AppClock() {
                final AtomicReference<Instant> time=new AtomicReference<>(NOW);
                @Override public Instant now(){return time.get();}
                @Override public void moveTo(Instant target){time.set(target);}
            };
        }
    }
    @Autowired AppClock clock;
    @Autowired DemoService demo;
    @Autowired DemoScenarioService scenarios;
    @Autowired WaitingInsightService insights;
    @Autowired RideRequestService rides;
    @Autowired RideRequestRepository requests;
    @Autowired MemberRepository members;
    @Autowired HubRepository hubs;
    @Autowired PartyRepository parties;
    @Autowired PartyService views;
    @Autowired PartyChatService chat;
    @org.springframework.boot.test.mock.mockito.SpyBean PaymentService payments;
    @Autowired PaymentOrderRepository orders;
    @Autowired PartyJoinRepository joins;
    @Autowired BoardingCaseRepository cases;
    @Autowired FareObjectionRepository objections;
    @Autowired SettlementRepository settlements;
    @Autowired TransferRequestRepository transfers;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    long me;
    @BeforeEach void reset() {
        PaymentTestSupport.clear(jdbc);
        jdbc.update("delete from party_message");
        transfers.deleteAll();settlements.deleteAll();requests.deleteAll();parties.deleteAll();
        jdbc.update("delete from member_restriction");jdbc.update("update member set no_show_count=0");
        clock.moveTo(NOW);me=members.findByEmail("demo-f1@daejin.ac.kr").orElseThrow().getId();
    }
    void request() {
        var hub=hubs.findAll().getFirst();
        rides.create(me,new RideRequestService.NewRequest(Direction.FROM_HUB,hub.getId(),NOW.plusSeconds(3600),NOW.plusSeconds(7200),null,"내 목적지",37.833,127.146,"사용자"));
    }
    PartyService.PartyView current() { return views.openParties(me).getFirst(); }
    MockHttpSession session() { var s=new MockHttpSession();s.setAttribute("memberId",me);return s; }
    @Test void roleChoiceCreatesFartherSampleAndKeepsMyPrepaymentForMe() {
        request();demo.matchNow(me,DemoService.Role.PASSENGER);
        var p=current();assertThat(p.payer()).isFalse();
        var mine=requests.findById(p.myRequestId()).orElseThrow();
        var payer=requests.findById(p.members().stream().filter(m->m.memberId()==p.payerId()).findFirst().orElseThrow().requestId()).orElseThrow();
        assertThat(mine.origin().distanceKm(payer.point())).isGreaterThan(mine.origin().distanceKm(mine.point()));
        var before=clock.now();var progress=scenarios.next(me);
        assertThat(clock.now()).isEqualTo(before);assertThat(progress.message()).contains("내 선결제");
        assertThat(orders.findByPartyIdAndMemberIdAndPurpose(p.id(),me,PaymentPurpose.PREPAY).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REQUIRED);
        assertThat(chat.read(me,p.id(),null).messages()).anyMatch(m->m.text().contains("봬요"));
        assertThat(chat.read(me,p.id(),null).messages()).anyMatch(m->m.text().equals("결제했어요"));
    }
    @Test void payerRoleAndNextStepReachCallDepartureAndSettlement() {
        request();demo.matchNow(me,DemoService.Role.PAYER);assertThat(current().payer()).isTrue();
        scenarios.next(me);assertThat(current().status()).isEqualTo(PartyStatus.READY);
        scenarios.next(me);assertThat(clock.now()).isEqualTo(current().departureAt().minusSeconds(600));
        views.arrive(me,current().id());scenarios.next(me);assertThat(current().status()).isEqualTo(PartyStatus.AWAITING_FARE);
        assertThat(chat.read(me,current().id(),null).messages()).anyMatch(m->m.text().startsWith("도착했어요"));
        var before=clock.now();assertThat(scenarios.next(me).message()).contains("영수증");assertThat(clock.now()).isEqualTo(before);
    }
    @Test void instantScenarioCreatesInstantRequestAndParty() {
        var p=scenarios.scenario(me,DemoScenarioService.Scenario.INSTANT,DemoService.Role.PASSENGER);
        assertThat(p.partyId()).isNotNull();assertThat(current().instant()).isTrue();assertThat(current().payer()).isFalse();
    }
    @Test void noShowScenarioVotesThenNextStepAdjudicatesAndFinalizes() {
        var prepared=scenarios.scenario(me,DemoScenarioService.Scenario.NO_SHOW,DemoService.Role.PAYER);
        assertThat(prepared.investigations()).hasSize(1);assertThat(prepared.investigations().getFirst().status()).isEqualTo("PENDING");
        assertThat(current().status()).isEqualTo(PartyStatus.OBJECTION_WINDOW);
        scenarios.next(me);
        assertThat(cases.findAll().getFirst().getStatus()).isEqualTo("CONFIRMED");
        assertThat(parties.findById(prepared.partyId()).orElseThrow().getStatus()).isIn(PartyStatus.SETTLED,PartyStatus.COMPLETED);
    }
    @Test void lateSampleOffersAndPrepaysJoinWithoutChangingPayer() {
        var p=scenarios.scenario(me,DemoScenarioService.Scenario.JOIN,DemoService.Role.PAYER);
        assertThat(current().members()).hasSize(4);assertThat(current().payer()).isTrue();
        assertThat(joins.findAll()).hasSize(1);assertThat(joins.findAll().getFirst().getState()).isEqualTo("CONFIRMED");
        assertThat(chat.read(me,p.partyId(),null).messages()).anyMatch(m->m.text().contains("합류했어요"));
    }
    @Test void sampleFareObjectionStopsBeforeRefunds() {
        scenarios.scenario(me,DemoScenarioService.Scenario.FARE_OBJECTION,DemoService.Role.PAYER);
        assertThat(current().status()).isEqualTo(PartyStatus.REVIEW_PENDING);assertThat(objections.count()).isEqualTo(1);
        assertThat(orders.findAll()).allMatch(o->o.getRefundedAmount()==0);
    }
    @Test void missingSamplePayerOffersSuccessionToMe() {
        scenarios.scenario(me,DemoScenarioService.Scenario.PAYER_NO_SHOW,DemoService.Role.PAYER);
        assertThat(current().status()).isEqualTo(PartyStatus.PAYER_PENDING);assertThat(current().successorId()).isEqualTo(me);
        assertThat(current().successionStatus()).isEqualTo("OFFERED");
    }
    @Test void explicitMySuccessionScenarioAcceptsAndPreservesPrepayment() {
        scenarios.scenario(me,DemoScenarioService.Scenario.MY_SUCCESSION,DemoService.Role.PAYER);
        var p=current();assertThat(p.payer()).isTrue();assertThat(p.successionStatus()).isEqualTo("ACCEPTED");
        assertThat(p.status()).isEqualTo(PartyStatus.AWAITING_FARE);
        assertThat(orders.findByPartyIdAndMemberIdAndPurpose(p.id(),me,PaymentPurpose.PREPAY).orElseThrow().getRefundedAmount()).isZero();
    }
    @Test void tPlusFiveAsksRideConfirmationWithoutRefund() {
        scenarios.scenario(me,DemoScenarioService.Scenario.RIDE_UNCONFIRMED,DemoService.Role.PAYER);
        assertThat(current().status()).isEqualTo(PartyStatus.RIDE_UNCONFIRMED);assertThat(current().canConfirmRide()).isTrue();
        assertThat(orders.findAll()).allMatch(o->o.getRefundedAmount()==0);
    }
    @Test void waitingInsightAlwaysUsesDemoClockAndFutureRound() {
        request();demo.advance(5);
        var mine=requests.findByActiveMemberId(me).orElseThrow();
        var insight=insights.of(mine.getId());
        assertThat(insight.nextCheckAt()).isAfter(clock.now()).isEqualTo(AppClock.nextSlotAfter(clock.now()));
    }
    @Test void automaticPaymentScenarioRejectsRealGatewayBeforeAnyMutation() {
        org.mockito.Mockito.doReturn(false).when(payments).simulated();
        assertThatThrownBy(()->scenarios.scenario(me,DemoScenarioService.Scenario.NO_SHOW,DemoService.Role.PAYER))
                .isInstanceOf(com.campustaximate.server.common.ApiException.class).hasMessageContaining("모의 결제");
        assertThat(requests.count()).isZero();assertThat(clock.now()).isEqualTo(NOW);
    }
    @Test void allNineAccountsCanLoginWithoutPasswordAndArbitraryEmailsAreRejected() throws Exception {
        for(String id:new String[]{"demo-f1","demo-f2","demo-f3","demo-f4","demo-m1","demo-m2","demo-m3","demo-m4","demo-admin"}) {
            var response=mvc.perform(post("/api/demo/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\""+id+"\"}"))
                    .andExpect(status().isOk()).andExpect(header().exists("Set-Cookie")).andReturn().getResponse();
            assertThat(response.getHeader("Set-Cookie")).contains("taximate_login=","HttpOnly");
        }
        mvc.perform(post("/api/demo/login").contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"student\"}")).andExpect(status().isBadRequest());
    }
}
