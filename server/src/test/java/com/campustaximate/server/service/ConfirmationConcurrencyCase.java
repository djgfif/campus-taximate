package com.campustaximate.server.service;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;
import com.campustaximate.matching.*;import com.campustaximate.server.common.*;
import com.campustaximate.server.domain.*;import com.campustaximate.server.repository.*;
import com.campustaximate.server.route.RouteClient;import com.campustaximate.server.payment.PaymentGateway;
import java.time.Instant;import java.util.*;import java.util.concurrent.*;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;import org.springframework.transaction.PlatformTransactionManager;import org.springframework.jdbc.core.JdbcTemplate;

/** 같은 요청을 포함하는 다른 조합이 별도 트랜잭션에서 동시에 확정을 시도한다. */
abstract class ConfirmationConcurrencyCase {
    private static final Instant NOW=Instant.parse("2026-11-12T00:00:00Z");
    @Autowired SlotService slots;@Autowired PartyRepository parties;@Autowired RideRequestRepository requests;
    @Autowired MemberRepository members;@Autowired HubRepository hubs;@Autowired PlatformTransactionManager manager;@Autowired JdbcTemplate jdbc;
    @MockBean AppClock clock;@MockBean RouteClient routes;@MockBean PaymentGateway gateway;
    void clear(){for(String table:new String[]{"party_join_member","party_join","boarding_vote","boarding_explanation","boarding_case","compensation_entry","admin_audit","ride_confirmation","party_change","party_member_term","payment_operation","payout_ledger","fare_objection","settlement_share","payment_order","transfer_request","settlement","party_assignment","ride_request_point","ride_request","party"})jdbc.update("delete from "+table);}
    @AfterEach void cleanup(){clear();}
    @Test @Tag("concurrency") void E03_samePersonInTwoConcurrentConfirmationsHasOnePartyForOneHundredRuns() throws Exception {
        when(clock.now()).thenReturn(NOW);var pool=Executors.newFixedThreadPool(2);
        try{for(int iteration=0;iteration<100;iteration++){
            clear();
            List<Long> memberIds=java.util.stream.IntStream.rangeClosed(1,3).mapToObj(i->members.findByEmail("demo-f"+i+"@daejin.ac.kr").orElseThrow().getId()).toList();
            List<MatchRequest> input=new RetryingTransactionTemplate(manager).execute(tx->{
                var hub=hubs.findAll().getFirst();List<MatchRequest> result=new ArrayList<>();
                for(int i=0;i<3;i++){
                    RideRequest r=new RideRequest();r.setMember(members.getReferenceById(memberIds.get(i)));r.setActiveMemberId(memberIds.get(i));r.setDirection(Direction.FROM_HUB);r.setHub(hub);r.setPointName("동시성 도착지");r.setPointLat(37.7-i*.01);r.setPointLon(127);r.setRequestedAt(NOW);r.setWindowStart(NOW.plusSeconds(1800));r.setWindowEnd(NOW.plusSeconds(3600));r.setSoloFare(10000);r.setSoloKm(5+i);r.setSoloSeconds(600);requests.save(r);
                    result.add(new MatchRequest(r.getId(),memberIds.get(i),new com.campustaximate.matching.Hub(hub.getId(),new GeoPoint(hub.getLat(),hub.getLon())),Gender.FEMALE,r.getWindowStart(),r.getWindowEnd(),r.point(),5+i,600));
                }return result;
            });
            Map<Long,RideRequest> snapshot=new HashMap<>();requests.findByStatus(RequestStatus.WAITING).forEach(r->snapshot.put(r.getId(),r));
            var first=new ConfirmedGroup(List.of(input.get(0),input.get(1)),input.get(1),NOW.plusSeconds(1800));
            var second=new ConfirmedGroup(List.of(input.get(0),input.get(2)),input.get(2),NOW.plusSeconds(1800));
            var verified=new RouteGroupVerifier.VerifiedRoutes(List.of(new RouteClient.Route(5,600,10000),new RouteClient.Route(6,600,10000)),List.of(300,600),15000,NOW.plusSeconds(1800),null);
            var barrier=new CyclicBarrier(2);Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(slots);
            List<Callable<Void>> work=List.of(()->{barrier.await(10,TimeUnit.SECONDS);new RetryingTransactionTemplate(manager).executeWithoutResult(tx->org.springframework.test.util.ReflectionTestUtils.invokeMethod(target,"confirm",first,verified,snapshot,NOW));return null;},
                ()->{barrier.await(10,TimeUnit.SECONDS);new RetryingTransactionTemplate(manager).executeWithoutResult(tx->org.springframework.test.util.ReflectionTestUtils.invokeMethod(target,"confirm",second,verified,snapshot,NOW));return null;});
            for(var future:pool.invokeAll(work,20,TimeUnit.SECONDS))future.get(10,TimeUnit.SECONDS);
            assertThat(parties.count()).as("회차 %s",iteration).isEqualTo(1);assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(2);
            assertThat(requests.findById(input.getFirst().id()).orElseThrow().getParty()).isNotNull();
            assertThat(jdbc.queryForObject("select count(distinct party_id) from ride_request where status='MATCHED'",Long.class)).isEqualTo(1);
        }}finally{pool.shutdownNow();}
        verifyNoInteractions(routes,gateway);
    }
}
