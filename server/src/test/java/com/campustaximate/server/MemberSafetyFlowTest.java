package com.campustaximate.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campustaximate.matching.Direction;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import com.campustaximate.server.service.DemoService;
import com.campustaximate.server.service.ReminderService;
import com.campustaximate.server.service.ReportPolicy;
import com.campustaximate.server.service.MemberSafetyService;
import com.campustaximate.server.service.NoShowPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@TestPropertySource(properties = "app.upload-dir=target/test-uploads")
class MemberSafetyFlowTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private AppClock clock;
    @Autowired private MemberSafetyService safety;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;
    @Autowired private DemoService demo;
    @Autowired private ReminderService reminders;
    @Autowired private MemberRepository members;
    @Autowired private HubRepository hubs;
    @Autowired private PartyRepository parties;
    @Autowired private RideRequestRepository requests;
    @Autowired private SettlementRepository settlements;
    @Autowired private TransferRequestRepository transfers;
    @Autowired private MemberAvoidRepository avoids;
    @Autowired private MemberReportRepository reports;
    @Autowired private MemberRestrictionRepository restrictions;
    @Autowired private PartyAssignmentRepository assignments;
    @Autowired private MailOutboxRepository outbox;
    @org.springframework.boot.test.mock.mockito.SpyBean private com.campustaximate.server.service.MailService mail;
    @org.springframework.boot.test.mock.mockito.SpyBean private com.campustaximate.server.route.RouteClient routes;

    @BeforeEach
    void reset() {
        clean();
        clock.moveTo(AppClock.slotOf(Instant.now()).plusSeconds(60));
    }

    @AfterEach
    void clean() {
        reports.deleteAll();
        avoids.deleteAll();
        restrictions.deleteAll();
        assignments.deleteAll();
        transfers.deleteAll();
        settlements.deleteAll();
        requests.deleteAll();
        parties.deleteAll();
    }

    @Test
    void onlyFormerPartyMembersCanReportOrAvoid() throws Exception {
        long a = id("demo-f1"), b = id("demo-f2"), outsider = id("demo-m1");
        MockHttpSession actor = session(a);
        Party party = assignedParty(a, b);
        mvc.perform(put("/api/members/" + a + "/avoid").session(actor)).andExpect(status().isBadRequest());
        report(actor, party.getId(), a).andExpect(status().isBadRequest());
        mvc.perform(put("/api/members/" + outsider + "/avoid").session(actor)).andExpect(status().isForbidden());
        report(actor, party.getId(), outsider).andExpect(status().isForbidden());
        mvc.perform(delete("/api/members/" + outsider + "/avoid").session(actor)).andExpect(status().isForbidden());
        report(session(outsider), party.getId(), b).andExpect(status().isForbidden());
        mvc.perform(get("/api/parties/" + party.getId() + "/members").session(session(outsider)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/members/" + b + "/avoid")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/avoids")).andExpect(status().isUnauthorized());
        // 배정 이력만 남아도 권한이 유지된다. 현재 ride_request.party에 의존하지 않는다.
        mvc.perform(put("/api/members/" + b + "/avoid").session(actor)).andExpect(status().isOk());
        report(actor, party.getId(), b).andExpect(status().isOk());
        assertThat(reports.count()).isEqualTo(1);
        mvc.perform(post(reportPath(party.getId(), b)).session(actor).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reason", "NO_SHOW")))).andExpect(status().isBadRequest());
        mvc.perform(post(reportPath(party.getId(), b)).session(actor).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reason", "OTHER", "memo", "가".repeat(201)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportAlsoAvoidsAndIsCountedOncePerParty() throws Exception {
        long a = id("demo-f1"), b = id("demo-f2"), c = id("demo-f3"), d = id("demo-f4");
        Party party = assignedParty(a, b, c, d);
        report(session(a), party.getId(), b).andExpect(status().isOk());
        report(session(a), party.getId(), b).andExpect(status().isConflict());
        report(session(c), party.getId(), b).andExpect(status().isOk());
        report(session(d), party.getId(), b).andExpect(status().isOk());
        assertThat(reports.count()).isEqualTo(3);
        assertThat(reports.countReportedParties(b)).isEqualTo(1);
        assertThat(restrictions.count()).isZero();
        assertThat(avoids.existsByMemberIdAndAvoidedMemberId(a, b)).isTrue();
        JsonNode own = body(mvc.perform(get("/api/avoids").session(session(a))).andExpect(status().isOk()));
        assertThat(own.get(0).get("memberId").asLong()).isEqualTo(b);
        assertThat(body(mvc.perform(get("/api/avoids").session(session(b))).andExpect(status().isOk()))).isEmpty();
        // 상대는 다른 사람이 만든 회피를 지울 수 없다.
        mvc.perform(delete("/api/members/" + a + "/avoid").session(session(b))).andExpect(status().isOk());
        assertThat(avoids.existsByMemberIdAndAvoidedMemberId(a, b)).isTrue();
        mvc.perform(put("/api/members/" + a + "/avoid").session(session(b))).andExpect(status().isOk());
        mvc.perform(delete("/api/members/" + b + "/avoid").session(session(a))).andExpect(status().isOk());
        assertThat(avoids.existsByMemberIdAndAvoidedMemberId(a, b)).isFalse();
        assertThat(avoids.existsByMemberIdAndAvoidedMemberId(b, a)).isTrue();
    }

    @Test
    void reportsFromThreePartiesRestrictNewRequestsForSevenDays() throws Exception {
        long target = id("demo-f4");
        MockHttpSession user = session(target);
        Instant started = clock.now();
        for (String actor : List.of("demo-f1", "demo-f2", "demo-f3")) {
            long reporter = id(actor);
            Party party = assignedParty(reporter, target);
            report(session(reporter), party.getId(), target).andExpect(status().isOk());
            if (reports.countReportedParties(target) < 3) assertThat(state(user).get("restricted").asBoolean()).isFalse();
        }
        assertThat(restrictions.findAll()).hasSize(1);
        var saved = restrictions.findAll().get(0);
        assertThat(saved.getCreatedAt()).isBetween(started.minusMillis(1), clock.now());
        assertThat(saved.getExpiresAt()).isEqualTo(saved.getCreatedAt().plus(Duration.ofDays(7)));
        Instant expires = saved.getExpiresAt();
        assertThat(ReportPolicy.PARTY_THRESHOLD).isEqualTo(3);
        assertThat(ReportPolicy.RESTRICTION_DURATION).isEqualTo(Duration.ofDays(7));
        JsonNode state = state(user);
        assertThat(state.get("restricted").asBoolean()).isTrue();
        assertThat(state.get("transferRestricted").asBoolean()).isFalse();
        assertThat(Instant.parse(state.get("restriction").get("expiresAt").asText())).isEqualTo(expires);
        assertThat(state.get("restriction").get("message").asText()).contains("신고 누적으로", "까지 새 요청을 할 수 없어요");
        createRequest(user, 0).andExpect(status().isConflict());
        clock.moveTo(expires.minusSeconds(1));
        createRequest(user, 0).andExpect(status().isConflict());
        clock.moveTo(expires.plusMillis(1));
        assertThat(state(user).get("restricted").asBoolean()).isFalse();
        createRequest(user, 0).andExpect(status().isOk());
    }

    @Test
    void avoidedMembersAreNotMatchedAgain() throws Exception {
        long a = id("demo-f1"), b = id("demo-f2");
        for (int i = 0; i < 3; i++) createRequest(session(id("demo-f" + (i + 1))), i).andExpect(status().isOk());
        advanceToDeadline();
        JsonNode former = state(session(a)).get("parties").get(0);
        assertThat(former.get("members")).hasSize(3);
        List<RideRequest> old = requests.findByStatus(RequestStatus.MATCHED);
        old.forEach(r -> { r.clearParty(); r.finish(RequestStatus.COMPLETED); });
        requests.saveAll(old);
        // 현재 일행 연결을 지워도 슬롯 확정 때 남긴 배정 이력으로 지정할 수 있다.
        mvc.perform(put("/api/members/" + a + "/avoid").session(session(b))).andExpect(status().isOk());
        JsonNode history = body(mvc.perform(get("/api/history").session(session(a))).andExpect(status().isOk()));
        assertThat(history.get(0).get("partyIds").get(0).asLong()).isEqualTo(former.get("id").asLong());
        for (int i = 0; i < 4; i++) createRequest(session(id("demo-f" + (i + 1))), i).andExpect(status().isOk());
        advanceToDeadline();
        List<RideRequest> matched = requests.findByStatus(RequestStatus.MATCHED);
        assertThat(matched).hasSize(3);
        assertThat(matched.stream().map(RideRequest::getParty).map(Party::getId).distinct()).hasSize(1);
        List<Long> ids = matched.stream().map(r -> r.getMember().getId()).toList();
        assertThat(ids.contains(a) && ids.contains(b)).isFalse();
        assertThat(ids).contains(id("demo-f3"), id("demo-f4"));
        assertThat(ids.contains(a) || ids.contains(b)).isTrue();
    }

    @Test
    void unsentTransferGetsOneReminderMailAfterTwentyFourHours() {
        long payer = id("demo-f3"), unsent = id("demo-f1"), sent = id("demo-f2");
        Party party = assignedParty(payer, unsent, sent);
        Settlement settlement = settlements.save(new Settlement(party, 15000, null, null, null, null, clock.now()));
        TransferRequest pending = transfers.save(new TransferRequest(settlement, members.findById(unsent).orElseThrow(), 5000));
        TransferRequest alreadySent = new TransferRequest(settlement, members.findById(sent).orElseThrow(), 5000);
        alreadySent.setStatus(TransferStatus.SENT);
        transfers.save(alreadySent);
        long before = reminderCount();
        clock.moveTo(clock.now().plus(Duration.ofHours(24)).minusSeconds(1));
        reminders.remind(clock.now());
        assertThat(reminderCount()).isEqualTo(before);
        clock.moveTo(clock.now().plusSeconds(1));
        // 실제 슬롯/승차 스케줄러 경로도 안내 서비스를 부른다.
        demo.runNow();
        reminders.remind(clock.now());
        clock.moveTo(clock.now().plus(Duration.ofDays(1)));
        reminders.remind(clock.now());
        assertThat(reminderCount()).isEqualTo(before + 1);
        assertThat(transfers.findById(pending.getId()).orElseThrow().getRemindedAt()).isNotNull();
        assertThat(outbox.findAll()).filteredOn(m -> m.getSubject().equals("[캠퍼스 택시메이트] 미송금 안내"))
                .last().extracting(MailOutbox::getRecipient).isEqualTo(members.findById(unsent).orElseThrow().getEmail());
        assertThat(transfers.findById(alreadySent.getId()).orElseThrow().getRemindedAt()).isNull();
    }

    private long reminderCount() {
        return outbox.findAll().stream().filter(m -> m.getSubject().equals("[캠퍼스 택시메이트] 미송금 안내")).count();
    }

    private Party assignedParty(long... ids) {
        Party party = parties.save(new Party(Direction.FROM_HUB, hubs.findAll().get(0), null,
                members.findById(ids[0]).orElseThrow(), clock.now().minusSeconds(3600), 15000));
        party.setStatus(PartyStatus.COMPLETED);
        parties.save(party);
        for (long id : ids) {
            PartyAssignment assignment = new PartyAssignment();
            assignment.setParty(party);
            assignment.setMemberId(id);
            assignment.setRequestId(0);
            assignments.save(assignment);
        }
        return party;
    }

    @Test
    void repeatedNoShowsRestrictNewRequests() throws Exception {
        long target = id("demo-f4");
        MockHttpSession user = session(target);
        noShowOnce(user, target);
        assertThat(state(user).get("restricted").asBoolean()).isFalse();

        noShowOnce(user, target);
        JsonNode state = state(user);
        assertThat(state.get("restricted").asBoolean()).isTrue();
        assertThat(state.get("restriction").get("message").asText()).contains("노쇼가 반복되어");
        var first = restrictions.findAll().get(0);
        assertThat(first.getExpiresAt()).isEqualTo(first.getCreatedAt().plus(NoShowPolicy.FIRST_RESTRICTION));
        createRequest(user, 0).andExpect(status().isConflict());

        clock.moveTo(first.getExpiresAt().plusSeconds(1));
        noShowOnce(user, target);
        assertThat(restrictions.findAll()).hasSize(2);
        var second = restrictions.findAll().stream().max(java.util.Comparator.comparing(MemberRestriction::getExpiresAt)).orElseThrow();
        assertThat(second.getExpiresAt()).isEqualTo(second.getCreatedAt().plus(NoShowPolicy.SECOND_RESTRICTION));
        assertThat(NoShowPolicy.restrictionFor(1)).isNull();
        assertThat(NoShowPolicy.restrictionFor(2)).isEqualTo(Duration.ofDays(7));
        assertThat(NoShowPolicy.restrictionFor(3)).isEqualTo(Duration.ofDays(30));
    }

    /** 요청을 하나 만들고 그 요청을 노쇼로 기록한다. */
    private void noShowOnce(MockHttpSession user, long memberId) throws Exception {
        createRequest(user, 0).andExpect(status().isOk());
        long requestId = requests.findAll().stream().filter(r -> r.getMember().getId() == memberId && !r.isNoShow())
                .mapToLong(RideRequest::getId).max().orElseThrow();
        new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(status ->
                safety.recordNoShow(requests.findById(requestId).orElseThrow(), clock.now()));
        mvc.perform(delete("/api/requests/" + requestId).session(user));
    }

    private long id(String name) { return members.findByEmail(name + "@daejin.ac.kr").orElseThrow().getId(); }
    private MockHttpSession session(long memberId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("memberId", memberId);
        return session;
    }
    private String reportPath(long party, long member) { return "/api/parties/" + party + "/members/" + member + "/report"; }
    private ResultActions report(MockHttpSession session, long party, long member) throws Exception {
        return mvc.perform(post(reportPath(party, member)).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"RUDE\",\"memo\":\"불쾌한 언행이 있었어요\"}"));
    }
    private ResultActions createRequest(MockHttpSession session, int index) throws Exception {
        Instant start = AppClock.nextSlotAfter(clock.now()).plusSeconds(1800);
        double[][] points = {{37.785, 127.105}, {37.745, 127.075}, {37.700, 127.050}, {37.680, 127.040}};
        return mvc.perform(post("/api/requests").session(session).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("hubId", hubs.findAll().get(0).getId(), "direction", "FROM_HUB",
                        "windowStart", start.toString(), "windowEnd", start.plusSeconds(3600).toString(),
                        "pointName", "샘플", "pointLat", points[index][0], "pointLon", points[index][1]))));
    }
    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
    private JsonNode state(MockHttpSession session) throws Exception {
        return body(mvc.perform(get("/api/state").session(session)).andExpect(status().isOk()));
    }

    /** 4명이 안 되면 모집 마감 슬롯에 확정한다. 대기 중 요청의 가장 이른 마감까지 시간을 보낸다. */
    private void advanceToDeadline() {
        java.time.Instant deadline = requests.findAll().stream()
                .filter(r -> r.getStatus() == com.campustaximate.server.domain.RequestStatus.WAITING)
                .map(com.campustaximate.server.domain.RideRequest::resultBy)
                .min(java.time.Instant::compareTo).orElseThrow();
        long minutes = java.time.Duration.between(clock.now(), deadline).toMinutes() + 1;
        demo.advance((int) Math.max(5, minutes));
    }
}
