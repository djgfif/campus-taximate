package com.campustaximate.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.RoadLeg;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import com.campustaximate.server.route.RouteClient;
import com.campustaximate.server.service.SlotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 3차 서버 연결 수용 테스트. 외부 경로 호출 없이 저장과 매칭 결과를 검사한다. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:matching-connection;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.upload-dir=target/test-uploads", "app.features.sequential-pickup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class MatchingConnectionTest {
    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RideRequestRepository requests;
    @Autowired PartyRepository parties;
    @Autowired MemberRepository members;
    @Autowired HubRepository hubs;
    @Autowired SlotService slots;
    @Autowired com.campustaximate.server.service.SoloRouteLookup soloRoutes;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockBean RouteClient routes;
    @SpyBean AppClock clock;
    @SpyBean com.campustaximate.server.route.WalkEstimator walks;
    List<Hub> points;
    long a, b;

    @BeforeEach
    void prepare() {
        PaymentTestSupport.clear(jdbc);
        requests.deleteAll();
        parties.deleteAll();
        ((Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(soloRoutes, "entries")).clear();
        ((Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(soloRoutes, "calls")).clear();
        jdbc.update("delete from mail_outbox");
        doReturn(NOW).when(clock).now();
        a = members.findByEmail("demo-f1@daejin.ac.kr").orElseThrow().getId();
        b = members.findByEmail("demo-f2@daejin.ac.kr").orElseThrow().getId();
        School school = members.findById(a).orElseThrow().getSchool();
        points = new ArrayList<>();
        for (int i = 0; i < 4; i++) points.add(hubs.save(new Hub("검증 지점 " + i, "만나는 곳", 37 + i * 0.0002, 127, Set.of(school))));
        when(routes.simulated()).thenReturn(true);
        when(routes.routeAt(any(), anyList(), nullable(Instant.class))).thenAnswer(call -> {
            List<GeoPoint> stops = call.getArgument(1);
            int seconds = stops.size() == 1 ? 600 : 1200;
            return new RouteClient.Route(Math.abs(stops.getLast().lat() - 37) * 1000, seconds,
                    stops.size() == 1 ? 10000 : 15000, List.of(), Collections.nCopies(stops.size(), 600));
        });
        when(routes.legs(any(), anyMap())).thenAnswer(call -> {
            Map<String, GeoPoint> targets = call.getArgument(1);
            Map<String, RoadLeg> legs = new HashMap<>();
            targets.keySet().forEach(id -> legs.put(id, new RoadLeg(1, 300)));
            return legs;
        });
    }

    @AfterEach
    void clean() {
        PaymentTestSupport.clear(jdbc);
        requests.deleteAll();
        parties.deleteAll();
    }

    private Map<String, Object> departure(int destination) {
        Map<String, Object> body = new HashMap<>();
        body.put("direction", "FROM_HUB");
        body.put("hubId", points.getFirst().getId());
        body.put("windowStart", NOW.plusSeconds(1800).toString());
        body.put("windowEnd", NOW.plusSeconds(3600).toString());
        body.put("pointName", "도착지");
        body.put("pointLat", 37.01 + destination * 0.01);
        body.put("pointLon", 127);
        return body;
    }

    private ResultActions create(long member, Map<String, Object> body) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("memberId", member);
        return mvc.perform(post("/api/requests").session(session).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private void match() {
        Instant slot = NOW.plusSeconds(15 * 60);
        doReturn(slot).when(clock).now();
        slots.runLocked(() -> { slots.process(slot); return null; });
    }

    @Test
    void instantPointOverFiveMinutesIsRejectedBeforeRouteLookup() throws Exception {
        var body = departure(0);
        body.put("mode", "INSTANT");
        body.put("origin",Map.of("lat",37.002,"lon",127));
        body.put("pointIds",List.of(points.get(0).getId(),points.get(3).getId()));
        create(a, body).andExpect(status().isBadRequest());
        assertThat(requests.count()).isZero();
        verifyNoInteractions(routes);
    }

    @Test
    void instantWithNoTimeInputAcceptsFiveMinuteWalkingBoundary() throws Exception {
        var body = departure(0);
        body.put("mode", "INSTANT");
        body.remove("windowStart"); body.remove("windowEnd");
        body.put("origin",Map.of("lat",37.0018,"lon",127));
        create(a, body).andExpect(status().isOk());
        var request=requests.findAll().getFirst();
        // 지점 목록은 지연 로딩이라 세션 밖에서 읽지 않고 DB 값으로 확인한다.
        assertThat(jdbc.queryForObject("select walk_seconds from ride_request_point where request_id = ?", Integer.class, request.getId())).isEqualTo(300);
        assertThat(request.resultBy()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void fourSelectedPointsAreRejected() throws Exception {
        var body = departure(0);
        body.put("pointIds", points.stream().map(Hub::getId).toList());
        body.put("origin", Map.of("lat", 37, "lon", 127));
        create(a, body).andExpect(status().isBadRequest());
        assertThat(requests.count()).isZero();
    }

    @Test
    void walkingOverTenMinutesIsRejected() throws Exception {
        var body = departure(0);
        body.put("origin", Map.of("lat", 37.02, "lon", 127));
        create(a, body).andExpect(status().isBadRequest());
        assertThat(requests.count()).isZero();
    }

    @Test
    void multiplePointsRequireOrigin() throws Exception {
        var body = departure(0);
        body.put("pointIds", List.of(points.get(0).getId(), points.get(1).getId()));
        create(a, body).andExpect(status().isBadRequest());
    }

    @Test
    void twoRequestsChoosingTwoPointsMatchAtTheirCommonPointAndStoreT() throws Exception {
        var first = departure(0);
        first.put("pointIds", List.of(points.get(0).getId(), points.get(1).getId()));
        first.put("origin", Map.of("lat", 37, "lon", 127));
        var second = departure(1);
        second.put("pointIds", List.of(points.get(1).getId(), points.get(2).getId()));
        second.put("origin", Map.of("lat", 37.0004, "lon", 127));
        create(a, first).andExpect(status().isOk());
        create(b, second).andExpect(status().isOk());
        match();
        assertThat(parties.findAll()).hasSize(1);
        Party party = parties.findAll().getFirst();
        assertThat(party.getHub().getId()).isEqualTo(points.get(1).getId());
        assertThat(party.getDepartureAt()).isEqualTo(NOW.plusSeconds(40 * 60));
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from ride_request_point", Integer.class)).isEqualTo(4);
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).allMatch(r -> r.getSoloFare() == 10000);
    }

    @Test
    void longerArrivalRouteMovesDepartureEarlierAndStoresVerifiedT() throws Exception {
        Instant latest = NOW.plusSeconds(4 * 3600);
        AtomicInteger fullCalls = new AtomicInteger();
        // 이미 스텁된 목을 when(...)으로 다시 스텁하면 기존 답이 빈 목록으로 불려 예외가 난다. doAnswer로 바꾼다.
        doAnswer(call -> {
            List<GeoPoint> stops = call.getArgument(1);
            if (stops.size() == 1) return new RouteClient.Route(Math.abs(stops.getLast().lat() - 37) * 1000,
                    1200, 10000, List.of(), List.of(1200));
            List<Integer> legs = fullCalls.getAndIncrement() == 0 ? List.of(900, 900) : List.of(1200, 900);
            return new RouteClient.Route(20, legs.stream().mapToInt(Integer::intValue).sum(), 15000, List.of(), legs);
        }).when(routes).routeAt(any(), anyList(), nullable(Instant.class));
        for (int i = 0; i < 2; i++) {
            var body = departure(i);
            body.remove("windowStart");
            body.remove("windowEnd");
            body.put("timeBasis", "ARRIVE");
            body.put("arriveBy", latest.toString());
            body.put("earlyArrivalMinutes", 15);
            create(i == 0 ? a : b, body).andExpect(status().isOk());
        }
        match();
        assertThat(parties.findAll()).hasSize(1);
        assertThat(parties.findAll().getFirst().getDepartureAt()).isEqualTo(latest.minusSeconds(2100));
        assertThat(fullCalls).hasValue(3);
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).allMatch(r -> r.getWindowStart() == null
                && r.getDeadlineSlot().equals(latest.minusSeconds(1200 + 60 * 60)));
    }

    @Test
    void oldHubOnlyInputStillMatchesWithZeroWalking() throws Exception {
        create(a, departure(0)).andExpect(status().isOk());
        create(b, departure(1)).andExpect(status().isOk());
        match();
        assertThat(parties.findAll()).hasSize(1);
        assertThat(parties.findAll().getFirst().getHub().getId()).isEqualTo(points.getFirst().getId());
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).allMatch(r -> r.getMode() == RequestMode.RESERVATION
                && r.getTimeBasis() == TimeBasis.DEPART && r.getPoints().size() == 1 && r.getPoints().getFirst().getWalkSeconds() == 0);
    }

    @Test
    void sequentialPickupIsRejectedWhenFeatureIsOff() throws Exception {
        var body = departure(0);
        body.put("direction", "TO_SCHOOL");
        body.put("arriveBy", NOW.plusSeconds(4 * 3600).toString());
        create(a, body).andExpect(status().isBadRequest());
        assertThat(requests.count()).isZero();
    }

    @Test
    void reservationIsLimitedBySeoulNextDay() throws Exception {
        var body = departure(0);
        body.put("windowStart", "2026-11-11T15:00:00Z");
        body.put("windowEnd", "2026-11-11T15:30:00Z");
        create(a, body).andExpect(status().isBadRequest());
        body.put("windowStart", "2026-11-11T14:55:00Z");
        body.put("windowEnd", "2026-11-11T15:25:00Z");
        create(a, body).andExpect(status().isOk());
    }

    @Test
    void failedAnchorQuoteLeavesNoRequestOrPointRows() throws Exception {
        doThrow(new RouteClient.RouteUnavailableException("모의 지점 경로 장애", null))
                .when(routes).routeAt(eq(new GeoPoint(37.0002, 127)), anyList(), nullable(Instant.class));
        var body = departure(0);
        body.put("pointIds", List.of(points.get(0).getId(), points.get(1).getId()));
        body.put("origin", Map.of("lat", 37, "lon", 127));
        create(a, body).andExpect(status().isServiceUnavailable());
        assertThat(requests.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ride_request_point", Integer.class)).isZero();
    }

    @Test
    void inactiveOrUncheckedPointsAreRejected() throws Exception {
        jdbc.update("update hub set active=false where id=?", points.getFirst().getId());
        create(a, departure(0)).andExpect(status().isBadRequest());
        jdbc.update("update hub set active=true, field_checked=false where id=?", points.getFirst().getId());
        create(a, departure(0)).andExpect(status().isBadRequest());
    }

    @Test
    void instantArrivalIsRejectedEvenWithPlentyOfTime() throws Exception {
        var body = departure(0);
        body.put("mode", "INSTANT");
        body.put("timeBasis", "ARRIVE");
        body.put("arriveBy", NOW.plusSeconds(3 * 3600).toString());
        create(a, body).andExpect(status().isBadRequest());
        assertThat(requests.count()).isZero();
        verifyNoInteractions(routes);
    }

    @Test
    void arrivalReservationLooksUpSoloTwiceAtSpecifiedTimes() throws Exception {
        Instant latest = NOW.plusSeconds(4 * 3600);
        var body = departure(0);
        body.put("timeBasis", "ARRIVE");
        body.put("arriveBy", latest.toString());
        create(a, body).andExpect(status().isOk());
        GeoPoint hub = new GeoPoint(points.getFirst().getLat(), points.getFirst().getLon());
        verify(routes).routeAt(eq(hub), anyList(), eq(latest.minusSeconds(3600)));
        verify(routes).routeAt(eq(hub), anyList(), eq(latest.minusSeconds(600)));
        verify(routes, times(2)).routeAt(eq(hub), anyList(), any());
    }

    @Test
    void instantDepartureUsesOneCurrentSoloLookup() throws Exception {
        var body = departure(1);
        body.put("mode", "INSTANT");
        body.put("timeBasis", "DEPART");
        body.remove("windowStart");body.remove("windowEnd");
        create(b, body).andExpect(status().isOk());
        verify(routes, times(1)).routeAt(any(), anyList(), isNull());
    }

    @Test
    void instantRequestsCanConfirmOnNonReservationMinute() throws Exception {
        var first = departure(0);
        var second = departure(1);
        first.put("mode", "INSTANT");
        second.put("mode", "INSTANT");
        first.put("origin",Map.of("lat",37.0005,"lon",127));
        second.put("origin",Map.of("lat",37.0005,"lon",127));
        create(a, first).andExpect(status().isOk());
        create(b, second).andExpect(status().isOk());
        Instant slot = NOW.plusSeconds(60);
        doReturn(slot).when(clock).now();
        slots.runLocked(() -> { slots.process(slot); return null; });
        assertThat(parties.findAll()).hasSize(1);
        assertThat(parties.findAll().getFirst().getDepartureAt()).isEqualTo(NOW.plusSeconds(60+600));
        verify(routes,atLeastOnce()).routeAt(any(),anyList(),eq(NOW.plusSeconds(60+600)));
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).allMatch(r->r.getRequestedAt().equals(NOW)&&r.getPoints().getFirst().getWalkSeconds()<=180);
    }

    @Test
    void expirationForMultiplePointsIsAppliedOncePerRequest() throws Exception {
        var body = departure(0);
        body.put("mode", "INSTANT");
        body.put("pointIds", List.of(points.get(0).getId(), points.get(1).getId()));
        body.put("origin", Map.of("lat", 37, "lon", 127));
        create(a, body).andExpect(status().isOk());
        assertThat(requests.findAll().getFirst().resultBy()).isEqualTo(NOW.plusSeconds(300));
        Instant slot = NOW.plusSeconds(300);
        doReturn(slot).when(clock).now();
        slots.runLocked(() -> { slots.process(slot); slots.process(slot); return null; });
        assertThat(requests.findByStatus(RequestStatus.UNMATCHED)).hasSize(1);
        assertThat(requests.findByStatus(RequestStatus.WAITING)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from mail_outbox where subject=? and recipient=?", Integer.class,
                "[캠퍼스 택시메이트] 미성사", "demo-f1@daejin.ac.kr")).isEqualTo(1);
    }

    @Test
    void E02_walkUnavailableRejectsRequestWithoutSaving() throws Exception {
        doThrow(new com.campustaximate.server.route.WalkUnavailableException()).when(walks).estimate(any(), any());
        var body = departure(0);
        body.put("origin", Map.of("lat", 37.004, "lon", 127));
        create(a, body).andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("WALK_UNAVAILABLE"));
        assertThat(requests.findByStatus(RequestStatus.WAITING)).isEmpty();
    }

    @Test
    void walkingExactlyTenMinutesIsAcceptedAndMarkedEstimated() throws Exception {
        var body = departure(0);
        body.put("origin", Map.of("lat", 37.004, "lon", 127));
        create(a, body).andExpect(status().isOk());
        RideRequest request = requests.findByStatus(RequestStatus.WAITING).getFirst();
        assertThat(request.getPoints().getFirst().getWalkSeconds()).isEqualTo(600);
        assertThat(request.getPoints().getFirst().isWalkEstimated()).isTrue();
    }

    @Test void i03InstantUsesEngineTAndFiveMinutePrepayDeadline() throws Exception {
        for(int i=0;i<2;i++) {
            var body=departure(i);body.put("mode","INSTANT");body.remove("windowStart");body.remove("windowEnd");
            body.put("origin",Map.of("lat",37.001,"lon",127));create(i==0?a:b,body).andExpect(status().isOk());
        }
        Instant slot=NOW.plusSeconds(120);doReturn(slot).when(clock).now();
        slots.runLocked(()->{slots.process(slot);return null;});
        assertThat(parties.findAll()).hasSize(1);var party=parties.findAll().getFirst();
        assertThat(party.isInstant()).isTrue();assertThat(party.getDepartureAt()).isEqualTo(NOW.plusSeconds(720));
        assertThat(party.getPrepayDeadlineAt()).isEqualTo(NOW.plusSeconds(420));
        assertThat(party.freeCancelUntil()).isEqualTo(NOW.plusSeconds(420));
        verify(routes,atLeastOnce()).routeAt(any(),anyList(),eq(NOW.plusSeconds(720)));
        assertThat(jdbc.queryForObject("select count(*) from mail_outbox where subject=?",Integer.class,"[캠퍼스 택시메이트] 승차 지점으로 출발")).isEqualTo(1);
    }
    /** 즉시 일행은 즉시 출발한다. 예약자의 출발 가능 범위가 그 시각보다 늦게 시작하면 T를 미루지 않고 묶지 않는다. */
    @Test void instantIsNotHeldBackForLaterReservationWindow() throws Exception {
        var instant=departure(0);instant.put("mode","INSTANT");instant.remove("windowStart");instant.remove("windowEnd");
        var reservation=departure(1);reservation.put("windowStart",NOW.plusSeconds(900).toString());reservation.put("windowEnd",NOW.plusSeconds(1800).toString());
        create(a,instant).andExpect(status().isOk());create(b,reservation).andExpect(status().isOk());
        Instant slot=NOW.plusSeconds(60);doReturn(slot).when(clock).now();slots.runLocked(()->{slots.process(slot);return null;});
        assertThat(parties.findAll()).isEmpty();
        assertThat(requests.findByStatus(RequestStatus.WAITING)).hasSize(2);
    }
    /** 예약자의 출발 가능 범위가 즉시 예상 T를 포함하면 함께 묶이고, 출발은 즉시 규칙(확정 + 준비 + 배차 여유)을 따른다. */
    @Test void mixedInstantAndReservationDepartsAtInstantT() throws Exception {
        var instant=departure(0);instant.put("mode","INSTANT");instant.remove("windowStart");instant.remove("windowEnd");
        var reservation=departure(1);reservation.put("windowStart",NOW.plusSeconds(300).toString());reservation.put("windowEnd",NOW.plusSeconds(1800).toString());
        create(a,instant).andExpect(status().isOk());create(b,reservation).andExpect(status().isOk());
        Instant slot=NOW.plusSeconds(60);doReturn(slot).when(clock).now();slots.runLocked(()->{slots.process(slot);return null;});
        assertThat(parties.findAll()).hasSize(1);var party=parties.findAll().getFirst();
        assertThat(party.getDepartureAt()).isBetween(slot.plusSeconds(480),slot.plusSeconds(840));
        assertThat(party.getPrepayDeadlineAt()).isEqualTo(slot.plusSeconds(300));
        assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(2);
        verify(routes,atLeastOnce()).routeAt(any(),anyList(),eq(party.getDepartureAt()));
    }
    @Test void E01_secondaryTimeoutCreatesNoPartyAndNextRoundRetriesSuccessfully() throws Exception {
        create(a,departure(0)).andExpect(status().isOk());create(b,departure(1)).andExpect(status().isOk());
        var failing=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call->{List<GeoPoint> stops=call.getArgument(1);if(stops.size()>1&&failing.get())throw new RouteClient.RouteUnavailableException("모의 2차 시간 초과",new java.net.SocketTimeoutException());
            return new RouteClient.Route(5,stops.size()==1?600:1200,stops.size()==1?10000:15000,List.of(),Collections.nCopies(stops.size(),600));
        }).when(routes).routeAt(any(),anyList(),nullable(Instant.class));
        AppClock target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(clock);
        Instant first=NOW.plusSeconds(900);doReturn(first).when(target).now();slots.runLocked(()->{slots.process(first);return null;});
        assertThat(parties.count()).isZero();assertThat(requests.findByStatus(RequestStatus.WAITING)).hasSize(2);
        failing.set(false);Instant next=NOW.plusSeconds(1200);doReturn(next).when(target).now();slots.runLocked(()->{slots.process(next);return null;});
        assertThat(parties.count()).isEqualTo(1);assertThat(requests.findByStatus(RequestStatus.MATCHED)).hasSize(2);
    }
}
