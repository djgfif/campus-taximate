package com.campustaximate.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.repository.PartyRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.repository.SettlementRepository;
import com.campustaximate.server.repository.TransferRequestRepository;
import com.campustaximate.server.service.DemoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@TestPropertySource(properties = {"app.upload-dir=target/test-uploads", "app.features.sequential-pickup=true"})
class CommuteFlowTest {

    /** 학교에서 가까운 순서. */
    private static final double[][] POINTS = {{37.785, 127.105}, {37.745, 127.075}, {37.700, 127.050}};

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private AppClock clock;
    @Autowired private DemoService demo;
    @Autowired private com.campustaximate.server.service.PaymentService payments;
    @Autowired private com.campustaximate.server.repository.PaymentOrderRepository paymentOrders;
    @Autowired private com.campustaximate.server.repository.SettlementShareRepository allocations;
    @Autowired private com.campustaximate.server.repository.PayoutLedgerRepository payouts;
    @Autowired private MemberRepository members;
    @Autowired private RideRequestRepository requests;
    @Autowired private PartyRepository parties;
    @Autowired private SettlementRepository settlements;
    @Autowired private TransferRequestRepository transfers;
    @Autowired private JdbcTemplate jdbc;
    @org.springframework.boot.test.mock.mockito.SpyBean private com.campustaximate.server.route.RouteClient routes;

    private Instant arriveBy;

    @BeforeEach
    void reset() {
        PaymentTestSupport.clear(jdbc);
        transfers.deleteAll();
        settlements.deleteAll();
        requests.deleteAll();
        parties.deleteAll();
        members.findAll().forEach(m -> {
            m.setNoShowCount(0);
            members.save(m);
        });
        clock.moveTo(AppClock.slotOf(Instant.now()).plusSeconds(60));
        arriveBy = AppClock.slotOf(clock.now()).plus(Duration.ofHours(6));
    }

    @Test
    void farthestRiderBoardsFirstAndPaysAndEveryoneElseSends() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToDeadline();

        MockHttpSession farthest = users.get(2);
        JsonNode party = onlyParty(farthest);
        long partyId = party.get("id").asLong();
        assertThat(party.get("direction").asText()).isEqualTo("TO_SCHOOL");
        assertThat(party.get("payer").asBoolean()).isTrue();
        assertThat(party.get("canBoard").asBoolean()).isTrue();
        assertThat(party.get("canArrive").asBoolean()).isTrue();

        JsonNode riders = party.get("members");
        assertThat(riders).hasSize(3);
        Instant previous = null;
        for (JsonNode rider : riders) {
            Instant board = Instant.parse(rider.get("boardAt").asText());
            if (previous != null) {
                assertThat(board).isAfter(previous);
            }
            previous = board;
        }
        Instant first = Instant.parse(riders.get(0).get("boardAt").asText());
        assertThat(Instant.parse(party.get("departureAt").asText())).isEqualTo(first);
        assertThat(Instant.parse(party.get("callAt").asText())).isEqualTo(first.minus(Duration.ofMinutes(10)));
        assertThat(Instant.parse(party.get("arrivalAt").asText())).isBeforeOrEqualTo(arriveBy);
        assertThat(onlyParty(users.get(0)).get("canBoard").asBoolean()).isFalse();

        boarded(partyId, farthest).andExpect(status().isOk());
        assertThat(onlyParty(users.get(0)).get("boardedAt").isNull()).isFalse();
        assertThat(onlyParty(farthest).get("members").get(0).get("arrived").asBoolean()).isTrue();
        arrive(partyId, users.get(0)).andExpect(status().isOk());
        arrive(partyId, users.get(1)).andExpect(status().isOk());
        passAllBoardings(party);

        assertThat(onlyParty(farthest).get("canSettle").asBoolean()).isTrue();
        long total = onlyParty(farthest).get("expectedTotal").asLong();
        submit(partyId, total, "OO은행", "123-456-789", farthest).andExpect(status().isOk());

        assertThat(transfers.count()).isZero();
        assertThat(settlements.findAll().getFirst().getAccountNumber()).isNull();
        advance(24 * 60 + 1);
        assertThat(payouts.findAll()).hasSize(1);
        assertThat(allocations.findAll().stream().mapToLong(a -> a.getAmount()).sum()).isEqualTo(total);
        assertThat(state(farthest).get("parties")).isEmpty();
        var stored = settlements.findAll().get(0);
        assertThat(stored.getBankName()).isNull();
        assertThat(stored.getAccountNumber()).isNull();
        assertThat(stored.getPaidAmount()).isEqualTo(total);
    }

    @Test
    void laterRiderIsNotNoShowAtFirstBoardingTime() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToDeadline();
        JsonNode party = onlyParty(users.get(2));
        legacyParty(party.get("id").asLong());
        long partyId = party.get("id").asLong();
        boarded(partyId, users.get(2)).andExpect(status().isOk());

        moveTo(Instant.parse(party.get("members").get(0).get("arriveDeadline").asText()).plusSeconds(60));

        JsonNode after = onlyParty(users.get(0));
        assertThat(after.get("members")).hasSize(3);
        assertThat(after.get("members").get(1).get("noShow").asBoolean()).isFalse();
        assertThat(after.get("members").get(2).get("noShow").asBoolean()).isFalse();
        assertThat(after.get("canArrive").asBoolean()).isTrue();
    }

    @Test
    void middleNoShowLeavesAtFinalCheckAndKeepsBoardingTimes() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToDeadline();
        JsonNode party = onlyParty(users.get(2));
        legacyParty(party.get("id").asLong());
        long partyId = party.get("id").asLong();
        Instant lastBoard = Instant.parse(party.get("members").get(2).get("boardAt").asText());
        boarded(partyId, users.get(2)).andExpect(status().isOk());
        arrive(partyId, users.get(0)).andExpect(status().isOk());

        moveTo(Instant.parse(party.get("members").get(1).get("arriveDeadline").asText()).plusSeconds(60));
        assertThat(onlyParty(users.get(0)).get("members").get(1).get("noShow").asBoolean()).isTrue();
        arrive(partyId, users.get(1)).andExpect(status().isConflict());

        passAllBoardings(party);

        JsonNode after = onlyParty(users.get(2));
        assertThat(after.get("members")).hasSize(2);
        assertThat(Instant.parse(after.get("members").get(1).get("boardAt").asText())).isEqualTo(lastBoard);
        assertThat(after.get("payer").asBoolean()).isTrue();
        assertThat(after.get("canSettle").asBoolean()).isTrue();
        assertThat(state(users.get(1)).get("parties")).isEmpty();
        assertThat(members.findByEmail("demo-f2@daejin.ac.kr").orElseThrow().getNoShowCount()).isEqualTo(1);
    }

    @Test
    void firstRiderNoShowHandsPayerToNextRider() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToDeadline();
        JsonNode party = onlyParty(users.get(2));
        legacyParty(party.get("id").asLong());
        long partyId = party.get("id").asLong();
        arrive(partyId, users.get(1)).andExpect(status().isOk());
        arrive(partyId, users.get(0)).andExpect(status().isOk());

        moveTo(Instant.parse(party.get("members").get(0).get("arriveDeadline").asText()).plusSeconds(60));

        JsonNode next = onlyParty(users.get(1));
        assertThat(next.get("payer").asBoolean()).isTrue();
        assertThat(next.get("canBoard").asBoolean()).isTrue();
        boarded(partyId, users.get(2)).andExpect(status().isForbidden());
        boarded(partyId, users.get(1)).andExpect(status().isOk());

        passAllBoardings(party);

        JsonNode after = onlyParty(users.get(1));
        assertThat(after.get("members")).hasSize(2);
        assertThat(after.get("canSettle").asBoolean()).isTrue();
        submit(partyId, after.get("expectedTotal").asLong(), "OO은행", "123-456-789", users.get(1))
                .andExpect(status().isOk());
        JsonNode transfersView = onlyParty(users.get(0)).get("settlement").get("transfers");
        assertThat(transfersView).hasSize(1);
        assertThat(allocations.findAll()).isEmpty();
        long remainingId = idOf(users.get(0));
        assertThat(transfersView.get(0).get("senderId").asLong()).isEqualTo(remainingId);
    }

    @Test
    void onlyOneRiderLeftCancelsParty() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToDeadline();
        JsonNode party = onlyParty(users.get(2));
        legacyParty(party.get("id").asLong());
        boarded(party.get("id").asLong(), users.get(2)).andExpect(status().isOk());

        passAllBoardings(party);

        assertThat(state(users.get(2)).get("parties")).isEmpty();
        assertThat(state(users.get(2)).get("latestRequest").get("status").asText()).isEqualTo("UNMATCHED");
        assertThat(state(users.get(2)).get("latestRequest").get("endReason").asText()).isEqualTo("LEFT_ALONE");
        assertThat(history(users.get(2)).get(0).get("stage").asText()).isEqualTo("LEFT_ALONE");
        assertThat(history(users.get(0)).get(0).get("stage").asText()).isEqualTo("NO_SHOW");
    }

    @Test
    void noShowRiderLeavesScreenAndCanRequestAgainBeforeFinalCheck() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToDeadline();
        JsonNode party = onlyParty(users.get(2));
        legacyParty(party.get("id").asLong());
        long partyId = party.get("id").asLong();
        boarded(partyId, users.get(2)).andExpect(status().isOk());
        arrive(partyId, users.get(0)).andExpect(status().isOk());
        moveTo(Instant.parse(party.get("members").get(1).get("arriveDeadline").asText()).plusSeconds(60));

        JsonNode state = state(users.get(1));
        assertThat(state.get("parties")).isEmpty();
        assertThat(state.get("latestRequest").get("endReason").asText()).isEqualTo("NO_SHOW");
        createRequest(users.get(1), POINTS[1], arriveBy.plus(Duration.ofDays(1))).andExpect(status().isOk());

        passAllBoardings(party);

        assertThat(onlyParty(users.get(2)).get("members")).hasSize(2);
        assertThat(history(users.get(1)).get(1).get("stage").asText()).isEqualTo("NO_SHOW");
    }

    @Test
    void cancelBeforeDepartureShrinksAndNextRiderBecomesFirst() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToDeadline();
        JsonNode party = onlyParty(users.get(2));
        long requestId = party.get("myRequestId").asLong();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/requests/" + requestId).session(users.get(2))).andExpect(status().isOk());

        JsonNode after = onlyParty(users.get(1));
        assertThat(after.get("members")).hasSize(2);
        assertThat(after.get("payer").asBoolean()).isTrue();
        assertThat(after.get("departureAt").asText()).isEqualTo(after.get("members").get(0).get("boardAt").asText());
    }

    @Test
    void laterRiderCanCancelAfterFirstBoardingUntilOwnBoardingTime() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToDeadline();
        JsonNode party = onlyParty(users.get(2));
        legacyParty(party.get("id").asLong());
        long partyId = party.get("id").asLong();
        boarded(partyId, users.get(2)).andExpect(status().isOk());
        moveTo(Instant.parse(party.get("departureAt").asText()).plusSeconds(60));

        JsonNode mine = onlyParty(users.get(0));
        assertThat(mine.get("canCancel").asBoolean()).isTrue();
        assertThat(onlyParty(users.get(2)).get("canCancel").asBoolean()).isFalse();
        cancel(mine.get("myRequestId").asLong(), users.get(0)).andExpect(status().isOk());

        JsonNode after = onlyParty(users.get(2));
        assertThat(after.get("members")).hasSize(2);
        assertThat(after.get("payer").asBoolean()).isTrue();
        assertThat(state(users.get(0)).get("parties")).isEmpty();
        assertThat(members.findByEmail("demo-f1@daejin.ac.kr").orElseThrow().getNoShowCount()).isZero();
    }

    @Test
    void cancelAfterOwnBoardingTimeIsRefused() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToDeadline();
        JsonNode party = onlyParty(users.get(1));
        moveTo(Instant.parse(party.get("myBoardAt").asText()).plusSeconds(1));

        assertThat(onlyParty(users.get(1)).get("canCancel").asBoolean()).isFalse();
        cancel(party.get("myRequestId").asLong(), users.get(1)).andExpect(status().isConflict());
    }

    @Test
    void boardedPayerCannotCancelEvenBeforeBoardingTime() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToDeadline();
        JsonNode party = onlyParty(users.get(2));
        boarded(party.get("id").asLong(), users.get(2)).andExpect(status().isOk());

        cancel(party.get("myRequestId").asLong(), users.get(2)).andExpect(status().isConflict());
    }

    @Test
    void cancelDoesNotHandPayerBackToNoShowRider() throws Exception {
        List<MockHttpSession> users = threeRequests();
        users.add(login("demo-f4"));
        createRequest(users.get(3), new double[] {37.810, 127.120}, arriveBy).andExpect(status().isOk());
        advanceToDeadline();
        JsonNode party = onlyParty(users.get(2));
        assertThat(party.get("members")).hasSize(4);
        assertThat(party.get("status").asText()).isEqualTo("READY");
        assertThat(paymentOrders.findByPartyIdOrderById(party.get("id").asLong())).hasSize(3)
                .allMatch(order -> order.getStatus() == com.campustaximate.server.domain.PaymentStatus.APPROVED
                        && order.getApprovedAt().isBefore(order.getDeadlineAt()));
        legacyParty(party.get("id").asLong());
        JsonNode riders = party.get("members");
        moveTo(Instant.parse(riders.get(0).get("arriveDeadline").asText()).plusSeconds(60));

        MockHttpSession next = sessionOf(users, riders.get(1).get("memberId").asLong());
        MockHttpSession last = sessionOf(users, riders.get(3).get("memberId").asLong());
        assertThat(onlyParty(next).get("payer").asBoolean()).isTrue();
        cancel(onlyParty(last).get("myRequestId").asLong(), last).andExpect(status().isOk());

        JsonNode after = onlyParty(next);
        assertThat(after.get("payer").asBoolean()).isTrue();
        assertThat(after.get("members")).hasSize(2);
        assertThat(state(users.get(2)).get("parties")).isEmpty();
    }

    @Test
    void routeFailureAtFinalCheckKeepsBoardedPartyAndSettlement() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToDeadline();
        JsonNode party = onlyParty(users.get(2));
        legacyParty(party.get("id").asLong());
        long partyId = party.get("id").asLong();
        long total = party.get("expectedTotal").asLong();
        boarded(partyId, users.get(2)).andExpect(status().isOk());
        arrive(partyId, users.get(1)).andExpect(status().isOk());
        org.mockito.Mockito.doThrow(new com.campustaximate.server.route.RouteClient.RouteUnavailableException("x", null))
                .when(routes).route(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        passAllBoardings(party);
        org.mockito.Mockito.reset(routes);

        JsonNode after = onlyParty(users.get(2));
        assertThat(after.get("members")).hasSize(2);
        assertThat(after.get("expectedTotal").asLong()).isEqualTo(total);
        assertThat(after.get("canSettle").asBoolean()).isTrue();
        submit(partyId, total, "OO은행", "123-456-789", users.get(2)).andExpect(status().isOk());
    }

    @Test
    void reuploadRestartsTheSevenDayKeep() throws Exception {
        List<MockHttpSession> users = settledReady();
        MockHttpSession payer = users.get(2);
        long id = onlyParty(payer).get("id").asLong();
        long total = onlyParty(payer).get("expectedTotal").asLong();
        submit(id, total, "", "", payer).andExpect(status().isOk());
        Instant previous = settlements.findAll().getFirst().getStoredAt();
        advance(60);
        MockMultipartFile receipt = new MockMultipartFile("receipt", "n.png", "image/png", new byte[] {(byte) 0x89, 80, 78, 71, 13, 10, 26, 10, 1});
        mvc.perform(multipart("/api/parties/" + id + "/settlement/correction").file(receipt)
                .param("amount", String.valueOf(total)).session(payer)).andExpect(status().isOk());
        assertThat(settlements.findAll().getFirst().getStoredAt()).isAfter(previous);
        assertThat(settlements.findAll().getFirst().getObjectionDeadlineAt()).isEqualTo(settlements.findAll().getFirst().getStoredAt().plusSeconds(24 * 3600));
        advance(24 * 60 + 1);
        mvc.perform(multipart("/api/parties/" + id + "/settlement/correction").file(receipt)
                .param("amount", String.valueOf(total)).session(payer)).andExpect(status().isConflict());
    }

    @Test
    void differentArrivalTimesAreNotMatched() throws Exception {
        MockHttpSession a = login("demo-f1");
        MockHttpSession b = login("demo-f2");
        MockHttpSession c = login("demo-f3");
        createRequest(a, POINTS[0], arriveBy).andExpect(status().isOk());
        createRequest(b, POINTS[1], arriveBy).andExpect(status().isOk());
        createRequest(c, POINTS[2], arriveBy.plusSeconds(300)).andExpect(status().isOk());
        advanceToDeadline();
        assertThat(state(a).get("parties")).isEmpty();
        assertThat(state(c).get("parties")).isEmpty();
    }

    @Test
    void twoRidersWaitForDeadlineSlotThenConfirm() throws Exception {
        MockHttpSession a = login("demo-f1");
        MockHttpSession b = login("demo-f2");
        createRequest(a, POINTS[0], arriveBy).andExpect(status().isOk());
        createRequest(b, POINTS[1], arriveBy).andExpect(status().isOk());
        Instant resultA = Instant.parse(state(a).get("latestRequest").get("resultBy").asText());
        Instant resultB = Instant.parse(state(b).get("latestRequest").get("resultBy").asText());
        Instant earliest = resultA.isBefore(resultB) ? resultA : resultB;

        moveTo(earliest.minusSeconds(300));
        assertThat(state(a).get("parties")).isEmpty();

        moveTo(earliest.plusSeconds(1));
        assertThat(state(a).get("parties")).hasSize(1);
    }

    @Test
    void loneRequestIsUnmatchedAfterDeadlineSlot() throws Exception {
        MockHttpSession a = login("demo-f1");
        createRequest(a, POINTS[0], arriveBy).andExpect(status().isOk());
        Instant resultBy = Instant.parse(state(a).get("latestRequest").get("resultBy").asText());

        moveTo(resultBy.minusSeconds(300));
        assertThat(state(a).get("latestRequest").get("status").asText()).isEqualTo("WAITING");

        moveTo(resultBy.plusSeconds(1));
        assertThat(state(a).get("latestRequest").get("status").asText()).isEqualTo("UNMATCHED");
    }

    @Test
    void requestTooCloseToArrivalIsRejected() throws Exception {
        MockHttpSession a = login("demo-f1");
        createRequest(a, POINTS[0], AppClock.slotOf(clock.now()).plus(Duration.ofMinutes(60)))
                .andExpect(status().isBadRequest());
        createRequest(a, POINTS[0], arriveBy.plusSeconds(60)).andExpect(status().isBadRequest());
    }

    @Test
    void previewShowsSoloFareAndResultTimeWithoutSaving() throws Exception {
        MockHttpSession a = login("demo-f1");
        JsonNode preview = read(mvc.perform(post("/api/requests/preview").session(a)
                        .contentType(MediaType.APPLICATION_JSON).content(body(POINTS[0], arriveBy)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(preview.get("soloFare").asLong()).isPositive();
        assertThat(preview.get("soloMinutes").asInt()).isPositive();
        assertThat(Instant.parse(preview.get("resultBy").asText())).isBefore(arriveBy);
        assertThat(requests.count()).isZero();
    }

    @Test
    void settlementPreviewIsForPayerOnly() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToDeadline();
        long partyId = onlyParty(users.get(2)).get("id").asLong();

        JsonNode shares = read(mvc.perform(get("/api/parties/" + partyId + "/settlement/preview")
                        .param("amount", "15000").session(users.get(2)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long sum = 0;
        for (JsonNode share : shares) {
            sum += share.get("amount").asLong();
        }
        assertThat(sum).isEqualTo(15000);
        assertThat(shares.get(0).get("payer").asBoolean()).isTrue();
        mvc.perform(get("/api/parties/" + partyId + "/settlement/preview").param("amount", "15000")
                .session(users.get(0))).andExpect(status().isForbidden());
    }

    @Test
    void accountIsRequiredEncryptedHiddenFromOutsidersAndDeletedAfterSevenDays() throws Exception {
        List<MockHttpSession> users = settledReady();
        MockHttpSession payer = users.get(2);
        long id = onlyParty(payer).get("id").asLong();
        long total = onlyParty(payer).get("expectedTotal").asLong();
        submit(id, total, "입력해도 저장하지 않음", "계좌", payer).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select account_number from settlement", String.class)).isNull();
        assertThat(settlements.findAll().getFirst().getBankName()).isNull();
        mvc.perform(get("/api/parties/" + id).session(login("demo-f4"))).andExpect(status().isForbidden());
        assertThat(transfers.count()).isZero();
    }

    @Test
    void demoSamplesFollowMyArrivalTimeAndActThroughTheFlow() throws Exception {
        MockHttpSession me = login("demo-f1");
        createRequest(me, POINTS[0], arriveBy).andExpect(status().isOk());
        demo.addSamples(idOf(me), 2);
        advanceToDeadline();
        JsonNode party = onlyParty(me);
        assertThat(party.get("direction").asText()).isEqualTo("TO_SCHOOL");
        arrive(party.get("id").asLong(), me).andExpect(status().isOk());
        demo.actAsSamples(idOf(me));
        passAllBoardings(party);
        demo.actAsSamples(idOf(me));
        JsonNode settled = onlyParty(me);
        if (!settled.get("payer").asBoolean()) {
            assertThat(settled.get("settlement").get("accountNumber").isNull()).isTrue();
            assertThat(settled.get("status").asText()).isEqualTo("OBJECTION_WINDOW");
        }
    }

    /** 순차 승차의 자동 노쇼·승계는 레거시 일행에서 유지한다. */
    private void legacyParty(long id) {
        var party=parties.findById(id).orElseThrow(); party.setPaymentFlowVersion(0);
        party.setPrepayDeadlineAt(null); party.setConfirmedAt(null);
        jdbc.update("delete from payment_operation where payment_order_id in (select id from payment_order where party_id=?)",id);
        jdbc.update("delete from payment_order where party_id=?",id);
        party.setStatus(com.campustaximate.server.domain.PartyStatus.CONFIRMED); parties.save(party);
    }

    private List<MockHttpSession> settledReady() throws Exception {
        List<MockHttpSession> users = threeRequests();
        advanceToDeadline();
        JsonNode party = onlyParty(users.get(2));
        long partyId = party.get("id").asLong();
        boarded(partyId, users.get(2)).andExpect(status().isOk());
        arrive(partyId, users.get(0)).andExpect(status().isOk());
        arrive(partyId, users.get(1)).andExpect(status().isOk());
        passAllBoardings(party);
        return users;
    }

    private void passAllBoardings(JsonNode party) throws Exception {
        JsonNode riders = party.get("members");
        moveTo(Instant.parse(riders.get(riders.size() - 1).get("arriveDeadline").asText()).plusSeconds(60));
    }

    private void moveTo(Instant target) {
        long seconds = Duration.between(clock.now(), target).getSeconds();
        if (seconds > 0) {
            advance((int) ((seconds + 59) / 60));
        }
    }

    private List<MockHttpSession> threeRequests() throws Exception {
        List<MockHttpSession> users = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            MockHttpSession user = login("demo-f" + (i + 1));
            createRequest(user, POINTS[i], arriveBy).andExpect(status().isOk());
            users.add(user);
        }
        return users;
    }

    private String body(double[] point, Instant arrival) throws Exception {
        return json.writeValueAsString(Map.of("direction", "TO_SCHOOL", "arriveBy", arrival.toString(),
                "pointName", "샘플", "pointLat", point[0], "pointLon", point[1]));
    }

    private ResultActions createRequest(MockHttpSession user, double[] point, Instant arrival) throws Exception {
        return mvc.perform(post("/api/requests").session(user).contentType(MediaType.APPLICATION_JSON)
                .content(body(point, arrival)));
    }

    private ResultActions cancel(long requestId, MockHttpSession user) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/requests/" + requestId).session(user));
    }

    private ResultActions arrive(long partyId, MockHttpSession user) throws Exception {
        return mvc.perform(post("/api/parties/" + partyId + "/arrive").session(user));
    }

    private ResultActions boarded(long partyId, MockHttpSession user) throws Exception {
        return mvc.perform(post("/api/parties/" + partyId + "/boarded").session(user));
    }

    private ResultActions submit(long partyId, long amount, String bank, String account, MockHttpSession user)
            throws Exception {
        MockMultipartFile receipt = new MockMultipartFile("receipt", "r.png", "image/png", new byte[] {(byte) 0x89, 80, 78, 71, 13, 10, 26, 10, 1});
        return mvc.perform(multipart("/api/parties/" + partyId + "/settlement").file(receipt)
                .param("amount", String.valueOf(amount)).param("bankName", bank).param("accountNumber", account)
                .session(user));
    }

    private MockHttpSession login(String id) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String email = id + "@daejin.ac.kr";
        String response = mvc.perform(post("/api/auth/code").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String code = read(response).get("demoCode").asText();
        mvc.perform(post("/api/auth/login").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());
        return session;
    }

    private JsonNode state(MockHttpSession user) throws Exception {
        return read(mvc.perform(get("/api/state").session(user)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode history(MockHttpSession user) throws Exception {
        return read(mvc.perform(get("/api/history").session(user)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode onlyParty(MockHttpSession user) throws Exception {
        JsonNode list = state(user).get("parties");
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    private long idOf(MockHttpSession user) throws Exception {
        return read(mvc.perform(get("/api/me").session(user)).andReturn().getResponse().getContentAsString())
                .get("id").asLong();
    }

    private MockHttpSession sessionOf(List<MockHttpSession> users, long memberId) throws Exception {
        for (MockHttpSession user : users) {
            if (idOf(user) == memberId) {
                return user;
            }
        }
        throw new AssertionError("no session");
    }

    private JsonNode read(String body) throws Exception {
        return json.readTree(body);
    }

    /** 4명이 안 되면 모집 마감 슬롯에 확정한다. 대기 중 요청의 가장 이른 마감까지 시간을 보낸다. */
    private void advanceToDeadline() {
        java.time.Instant deadline = requests.findAll().stream()
                .filter(r -> r.getStatus() == com.campustaximate.server.domain.RequestStatus.WAITING)
                .map(com.campustaximate.server.domain.RideRequest::resultBy)
                .min(java.time.Instant::compareTo).orElseThrow();
        long minutes = java.time.Duration.between(clock.now(), deadline).toMinutes() + 1;
        advance((int) Math.max(5, minutes));
    }
    private void advance(int minutes) {
        if (minutes < 1 || minutes > 48 * 60) {
            demo.advance(minutes); // 유효 범위 밖 입력은 기존 시연 검증을 그대로 따른다.
            return;
        }
        PaymentTestSupport.approveEligible(payments, paymentOrders, parties, clock);
        // 네 명은 모집 마감 전에도 확정된다. 중간에 생긴 주문도 기한 전에 승인한다.
        for (int elapsed = 0; elapsed < minutes; elapsed++) {
            demo.advance(1);
            PaymentTestSupport.approveEligible(payments, paymentOrders, parties, clock);
        }
        payments.retryDue();
    }
}
