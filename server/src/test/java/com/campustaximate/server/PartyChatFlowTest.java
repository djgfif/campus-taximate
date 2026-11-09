package com.campustaximate.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campustaximate.matching.Direction;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import com.campustaximate.server.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:party-chat;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.upload-dir=target/test-uploads"})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class PartyChatFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppClock clock;
    @Autowired PartyChatService chat;
    @Autowired SettlementService settlementService;
    @Autowired SlotService slots;
    @Autowired PartyRepository parties;
    @Autowired MemberRepository members;
    @Autowired HubRepository hubs;
    @Autowired RideRequestRepository requests;
    @Autowired PartyAssignmentRepository assignments;
    @Autowired PartyMessageRepository messages;
    @Autowired SettlementRepository settlements;
    @Autowired TransferRequestRepository transfers;
    @Autowired MailOutboxRepository outbox;
    @Autowired MemberReportRepository reports;
    @Autowired MemberSafetyService safety;
    @MockBean PushService push;
    @SpyBean ChangeBus changes;
    long a, b, c, outsider;

    @BeforeEach
    void prepare() {
        clean();
        clock.moveTo(AppClock.slotOf(Instant.now()).plusSeconds(60));
        a = id("demo-f1"); b = id("demo-f2"); c = id("demo-f3"); outsider = id("demo-m1");
    }

    @AfterEach
    void clean() {
        reports.deleteAll();
        messages.deleteAll();
        assignments.deleteAll();
        transfers.deleteAll();
        settlements.deleteAll();
        requests.deleteAll();
        parties.deleteAll();
    }

    @Test
    void onlyCurrentPartyMembersCanReadAndWriteMessages() throws Exception {
        Party party = party(a, b, c);
        PartyAssignment former = new PartyAssignment();
        former.setParty(party); former.setMemberId(outsider); former.setRequestId(0);
        assignments.save(former);
        clearInvocations(push, changes);
        long mailBefore = outbox.count();
        String text = "가".repeat(45);
        JsonNode first = body(send(a, party, text).andExpect(status().isOk()));
        assertThat(first.get("memberId").asLong()).isEqualTo(a);
        assertThat(first.get("nickname").asText()).isEqualTo(members.findById(a).orElseThrow().getNickname());
        verify(push).send(b, "[캠퍼스 택시메이트] 일행 메시지", "새 일행 메시지가 도착했어요.", "/");
        verify(push).send(c, "[캠퍼스 택시메이트] 일행 메시지", "새 일행 메시지가 도착했어요.", "/");
        verify(push, never()).send(eq(a), anyString(), anyString(), anyString());
        verify(changes).partyMessageChanged(party.getId(), first.get("id").asLong(), java.util.List.of(a, b, c));
        assertThat(outbox.count()).isEqualTo(mailBefore);
        read(b, party).andExpect(status().isOk());
        JsonNode second = body(send(b, party, "도착했어요").andExpect(status().isOk()));
        JsonNode delta = body(mvc.perform(get(url(party)).param("after", first.get("id").asText()).session(session(a)))
                .andExpect(status().isOk()));
        assertThat(delta.get("messages")).hasSize(1);
        assertThat(delta.get("messages").get(0).get("id").asLong()).isEqualTo(second.get("id").asLong());
        read(outsider, party).andExpect(status().isForbidden());
        send(outsider, party, "접근").andExpect(status().isForbidden());
        mvc.perform(get(url(party))).andExpect(status().isUnauthorized());
        mvc.perform(post(url(party)).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"접근\"}"))
                .andExpect(status().isUnauthorized());
        RideRequest canceled = rider(party, b);
        canceled.finish(RequestStatus.CANCELED);
        canceled = requests.save(canceled);
        read(b, party).andExpect(status().isForbidden());
        send(b, party, "취소 뒤").andExpect(status().isForbidden());
        canceled.clearParty(); requests.save(canceled);
        read(b, party).andExpect(status().isForbidden());
        RideRequest noShow = rider(party, c);
        noShow.markNoShow(clock.now()); requests.save(noShow);
        read(c, party).andExpect(status().isForbidden());
        send(c, party, "노쇼 뒤").andExpect(status().isForbidden());
        send(a, party, "남은 일행").andExpect(status().isOk());
    }

    @Test
    void chatClosesAfterSettlementOrTwentyFourHours() throws Exception {
        Party settled = party(a, b);
        send(a, settled, "정산 전").andExpect(status().isOk());
        Instant before = clock.now();
        finishSettlement(settled);
        Party saved = parties.findById(settled.getId()).orElseThrow();
        assertThat(saved.getSettledAt()).isBetween(before.minusMillis(1), clock.now().plusMillis(1));
        assertClosed(settled);
        assertThat(body(read(a, settled).andExpect(status().isOk())).get("messages")).hasSize(1);

        Party timed = party(a, b);
        Instant deadline = parties.findById(timed.getId()).orElseThrow().getDepartureAt().plus(Duration.ofHours(24));
        clock.moveTo(deadline.minusSeconds(1));
        send(a, timed, "마감 직전").andExpect(status().isOk());
        clock.moveTo(deadline.plusMillis(1));
        assertClosed(timed);
        timed = parties.findById(timed.getId()).orElseThrow();
        timed.setStatus(PartyStatus.UNSETTLED); parties.save(timed);
        assertClosed(timed);
    }

    @Test
    void messagesAreDeletedSevenDaysAfterPartyEnds() throws Exception {
        Party early = party(a, b), timed = party(a, b);
        send(a, early, "정산 완료 기준").andExpect(status().isOk());
        send(a, timed, "출발 후 24시간 기준").andExpect(status().isOk());
        finishSettlement(early);
        Instant earlyEnd = parties.findById(early.getId()).orElseThrow().getSettledAt();
        Instant timedEnd = parties.findById(timed.getId()).orElseThrow().getDepartureAt().plus(Duration.ofHours(24));
        clock.moveTo(earlyEnd.plus(Duration.ofDays(7)).minusSeconds(1));
        chat.purgeMessages();
        assertThat(messages.count()).isEqualTo(2);
        clock.moveTo(earlyEnd.plus(Duration.ofDays(7)).plusMillis(1));
        // 기존 슬롯 정리 경로에서 삭제한다.
        slots.process(AppClock.slotOf(clock.now()));
        assertThat(messages.findTop50ByPartyIdOrderByIdDesc(early.getId())).isEmpty();
        assertThat(messages.findTop50ByPartyIdOrderByIdDesc(timed.getId())).hasSize(1);
        clock.moveTo(timedEnd.plus(Duration.ofDays(7)).minusSeconds(1));
        chat.purgeMessages();
        assertThat(messages.count()).isEqualTo(1);
        clock.moveTo(timedEnd.plus(Duration.ofDays(7)).plusMillis(1));
        assertThat(body(read(a, timed).andExpect(status().isOk())).get("messages")).isEmpty();
        chat.purgeMessages();
        chat.purgeMessages();
        assertThat(messages.count()).isZero();
    }

    @Test
    void messageTextIsTrimmedAndLimitedTo200() throws Exception {
        Party party = party(a, b);
        JsonNode saved = body(send(a, party, "  안녕하세요  ").andExpect(status().isOk()));
        assertThat(saved.get("text").asText()).isEqualTo("안녕하세요");
        Instant created = Instant.parse(saved.get("createdAt").asText());
        assertThat(messages.findById(saved.get("id").asLong()).orElseThrow().getCreatedAt())
                .isEqualTo(created);
        send(a, party, "  " + "가".repeat(200) + "  ").andExpect(status().isOk());
        send(a, party, "가".repeat(201)).andExpect(status().isBadRequest());
        send(a, party, " \n\t ").andExpect(status().isBadRequest());
        send(a, party, "").andExpect(status().isBadRequest());
        mvc.perform(post(url(party)).session(session(a)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(messages.count()).isEqualTo(2);
    }

    @Test
    void clientMessageIdPreventsDuplicateSendAndRejectsReusedKeyWithDifferentText() throws Exception {
        Party party = party(a, b);
        String endpoint = url(party);
        String key = "retry-1";
        String payload = json.writeValueAsString(Map.of("text", "기다리고 있어요", "clientMessageId", key));
        clearInvocations(push, changes);
        JsonNode first = body(mvc.perform(post(endpoint).session(session(a))
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk()));
        JsonNode second = body(mvc.perform(post(endpoint).session(session(a))
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk()));
        assertThat(second.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(messages.count()).isEqualTo(1);
        verify(changes, times(1)).partyMessageChanged(eq(party.getId()), eq(first.get("id").asLong()), anyList());
        verify(push, times(1)).send(eq(b), anyString(), anyString(), eq("/"));
        mvc.perform(post(endpoint).session(session(a)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("text", "다른 내용", "clientMessageId", key))))
                .andExpect(status().isConflict());
    }

    @Test
    void cursorPagingLoadsOlderMessagesAndPaginatesOfflineBacklog() throws Exception {
        Party party = party(a, b);
        for (int i = 0; i < 65; i++) {
            messages.save(new PartyMessage(party, members.getReferenceById(a), "메시지 " + i, clock.now()));
        }
        JsonNode latest = body(read(b, party).andExpect(status().isOk()));
        assertThat(latest.get("messages")).hasSize(50);
        assertThat(latest.get("hasOlder").asBoolean()).isTrue();
        long oldestShown = latest.get("messages").get(0).get("id").asLong();
        JsonNode earlier = body(mvc.perform(get(url(party)).param("before", String.valueOf(oldestShown))
                .session(session(b))).andExpect(status().isOk()));
        assertThat(earlier.get("messages")).hasSize(15);
        assertThat(earlier.get("hasOlder").asBoolean()).isFalse();
        assertThat(earlier.get("messages").get(14).get("id").asLong()).isLessThan(oldestShown);

        JsonNode firstBatch = body(mvc.perform(get(url(party)).param("after", "0").session(session(b)))
                .andExpect(status().isOk()));
        assertThat(firstBatch.get("messages")).hasSize(50);
        assertThat(firstBatch.get("hasNewer").asBoolean()).isTrue();
        long end = firstBatch.get("messages").get(49).get("id").asLong();
        JsonNode secondBatch = body(mvc.perform(get(url(party)).param("after", String.valueOf(end))
                .session(session(b))).andExpect(status().isOk()));
        assertThat(secondBatch.get("messages")).hasSize(15);
        assertThat(secondBatch.get("hasNewer").asBoolean()).isFalse();
        mvc.perform(get(url(party)).param("after", "1").param("before", "100")
                .session(session(b))).andExpect(status().isBadRequest());
    }

    @Test
    void formerMemberCanReadOnlyPreDepartureMessagesAndCannotPost() throws Exception {
        Party party = party(a, b, c);
        JsonNode before = body(send(a, party, "떠나기 전").andExpect(status().isOk()));
        PartyAssignment membership = assignments.findByPartyIdAndMemberId(party.getId(), b).orElseThrow();
        membership.leave(clock.now(), before.get("id").asLong());
        assignments.save(membership);
        RideRequest leaving = rider(party, b);
        leaving.clearParty();
        leaving.finish(RequestStatus.CANCELED);
        requests.save(leaving);
        send(a, party, "떠난 뒤 비공개").andExpect(status().isOk());

        JsonNode formerView = body(read(b, party).andExpect(status().isOk()));
        assertThat(formerView.get("closed").asBoolean()).isTrue();
        assertThat(formerView.get("messages")).hasSize(1);
        assertThat(formerView.get("messages").get(0).get("id").asLong()).isEqualTo(before.get("id").asLong());
        JsonNode future = body(mvc.perform(get(url(party)).param("after", before.get("id").asText())
                .session(session(b))).andExpect(status().isOk()));
        assertThat(future.get("messages")).isEmpty();
        send(b, party, "이탈했지만 보내기").andExpect(status().isForbidden());
    }

    @Test
    void reportMessageMustBelongToReportedMemberAndPreservesEvidenceSnapshot() throws Exception {
        Party party = party(a, b);
        long fromB = body(send(b, party, "부적절한 발언").andExpect(status().isOk())).get("id").asLong();
        long fromA = body(send(a, party, "정상 메시지").andExpect(status().isOk())).get("id").asLong();
        mvc.perform(post("/api/parties/" + party.getId() + "/members/" + b + "/report")
                .session(session(a)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reason", "RUDE", "messageId", fromA))))
                .andExpect(status().isBadRequest());
        assertThat(reports.count()).isZero();
        mvc.perform(post("/api/parties/" + party.getId() + "/members/" + b + "/report")
                .session(session(a)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reason", "RUDE", "messageId", fromB))))
                .andExpect(status().isOk());
        MemberReport evidence = reports.findAll().get(0);
        assertThat(evidence.getMessageId()).isEqualTo(fromB);
        assertThat(evidence.getMessageSnapshot()).isEqualTo("부적절한 발언");
        messages.deleteAll();
        assertThat(reports.findById(evidence.getId()).orElseThrow().getMessageSnapshot())
                .isEqualTo("부적절한 발언");
    }

    @Test
    void chatRateLimitBlocksFloodingWithoutBlockingPreviousMessages() throws Exception {
        Party party = party(a, b);
        for (int i = 0; i < 10; i++) {
            send(a, party, "문자 " + i).andExpect(status().isOk());
        }
        JsonNode blocked = body(send(a, party, "도배").andExpect(status().isTooManyRequests()));
        assertThat(blocked.get("code").asText()).isEqualTo("CHAT_RATE_LIMIT");
        assertThat(messages.count()).isEqualTo(10);
        assertThat(body(read(b, party).andExpect(status().isOk())).get("messages")).hasSize(10);
    }

    private void assertClosed(Party party) throws Exception {
        JsonNode result = body(send(a, party, "마감 뒤").andExpect(status().isConflict()));
        assertThat(result.get("code").asText()).isEqualTo("CHAT_CLOSED");
        assertThat(body(read(a, party).andExpect(status().isOk())).get("closed").asBoolean()).isTrue();
    }

    private void finishSettlement(Party party) {
        party = parties.findById(party.getId()).orElseThrow();
        party.setStatus(PartyStatus.SETTLING); parties.save(party);
        Settlement settlement = settlements.save(new Settlement(party, 10000, null, null, null, null, clock.now()));
        TransferRequest transfer = new TransferRequest(settlement, members.findById(b).orElseThrow(), 5000);
        transfer.setStatus(TransferStatus.SENT); transfers.save(transfer);
        settlementService.markReceived(a, transfer.getId());
    }

    private Party party(long... ids) {
        Party party = parties.save(new Party(Direction.FROM_HUB, hubs.findAll().get(0), null,
                members.findById(ids[0]).orElseThrow(), clock.now().plusSeconds(3600), 10000));
        for (int i = 0; i < ids.length; i++) {
            RideRequest rider = new RideRequest();
            rider.setMember(members.findById(ids[i]).orElseThrow());
            rider.setDirection(Direction.FROM_HUB); rider.setHub(party.getHub());
            rider.setParty(party); rider.setPointName("승차 지점"); rider.setRideOrder(i + 1);
            rider.setStatus(RequestStatus.COMPLETED); rider.setArrived(true);
            requests.save(rider);
            PartyAssignment assignment = new PartyAssignment();
            assignment.setParty(party); assignment.setMemberId(ids[i]); assignment.setRequestId(rider.getId());
            assignments.save(assignment);
        }
        return parties.findById(party.getId()).orElseThrow();
    }

    private RideRequest rider(Party party, long id) {
        return requests.findByPartyOrderByRideOrder(party).stream().filter(r -> r.getMember().getId() == id).findFirst().orElseThrow();
    }
    private long id(String name) { return members.findByEmail(name + "@daejin.ac.kr").orElseThrow().getId(); }
    private MockHttpSession session(long id) {
        MockHttpSession session = new MockHttpSession(); session.setAttribute("memberId", id); return session;
    }
    private String url(Party party) { return "/api/parties/" + party.getId() + "/messages"; }
    private ResultActions read(long actor, Party party) throws Exception { return mvc.perform(get(url(party)).session(session(actor))); }
    private ResultActions send(long actor, Party party, String text) throws Exception {
        return mvc.perform(post(url(party)).session(session(actor)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("text", text))));
    }
    private JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
}
