package com.campustaximate.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.repository.PartyRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.repository.SettlementRepository;
import com.campustaximate.server.repository.TransferRequestRepository;
import com.campustaximate.server.service.DemoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@TestPropertySource(properties = "app.upload-dir=target/test-uploads")
class FlowTest {

    private static final double[][] POINTS = {{37.785, 127.105}, {37.745, 127.075}, {37.700, 127.050}};

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private AppClock clock;
    @Autowired private DemoService demo;
    @Autowired private MemberRepository members;
    @Autowired private RideRequestRepository requests;
    @Autowired private PartyRepository parties;
    @Autowired private SettlementRepository settlements;
    @Autowired private TransferRequestRepository transfers;

    private long hubId;

    @BeforeEach
    void reset() throws Exception {
        transfers.deleteAll();
        settlements.deleteAll();
        requests.deleteAll();
        parties.deleteAll();
        members.findAll().forEach(m -> {
            m.setNoShowCount(0);
            members.save(m);
        });
        clock.moveTo(AppClock.slotOf(Instant.now()).plusSeconds(60));
        JsonNode hubs = read(mvc.perform(get("/api/hubs").session(login("demo-f1"))).andReturn().getResponse().getContentAsString());
        hubId = hubs.get(0).get("id").asLong();
    }

    @Test
    void fullFlowFromRequestToCompleted() throws Exception {
        List<MockHttpSession> users = threeRequests();

        demo.advance(5);
        JsonNode party = onlyParty(users.get(0));
        long partyId = party.get("id").asLong();
        assertThat(party.get("members")).hasSize(3);
        assertThat(party.get("status").asText()).isEqualTo("CONFIRMED");

        for (MockHttpSession user : users) {
            mvc.perform(post("/api/parties/" + partyId + "/arrive").session(user)).andExpect(status().isOk());
        }
        demo.advance(30);
        MockHttpSession payer = payerOf(users);
        assertThat(onlyParty(payer).get("canSettle").asBoolean()).isTrue();

        long total = onlyParty(payer).get("expectedTotal").asLong();
        submit(partyId, total, payer).andExpect(status().isOk());
        JsonNode settled = onlyParty(payer);
        assertThat(settled.get("status").asText()).isEqualTo("SETTLING");
        assertThat(settled.get("settlement").get("warning").asBoolean()).isFalse();
        long sum = settled.get("settlement").get("paidAmount").asLong();
        assertThat(sum).isEqualTo(total);

        for (JsonNode transfer : settled.get("settlement").get("transfers")) {
            MockHttpSession sender = sessionOf(users, transfer.get("senderId").asLong());
            mvc.perform(post("/api/transfers/" + transfer.get("id").asLong() + "/sent").session(sender)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0}"))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/transfers/" + transfer.get("id").asLong() + "/received").session(payer))
                    .andExpect(status().isOk());
        }
        assertThat(state(payer).get("parties")).isEmpty();
        assertThat(state(payer).get("latestRequest").get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void cancelBeforeDepartureShrinksPartyAndRecalculates() throws Exception {
        List<MockHttpSession> users = threeRequests();
        demo.advance(5);
        JsonNode before = onlyParty(users.get(1));
        long requestId = onlyParty(users.get(0)).get("myRequestId").asLong();

        mvc.perform(delete("/api/requests/" + requestId).session(users.get(0))).andExpect(status().isOk());

        assertThat(state(users.get(0)).get("latestRequest").get("status").asText()).isEqualTo("CANCELED");
        assertThat(state(users.get(0)).get("parties")).isEmpty();
        JsonNode after = onlyParty(users.get(1));
        assertThat(after.get("members")).hasSize(2);
        assertThat(after.get("members").get(0).get("dropoffOrder").asInt()).isEqualTo(1);
        assertThat(after.get("id").asLong()).isEqualTo(before.get("id").asLong());
    }

    @Test
    void lastTwoCannotShrinkSoPartyIsCanceled() throws Exception {
        List<MockHttpSession> users = threeRequests();
        demo.advance(5);
        long first = onlyParty(users.get(0)).get("myRequestId").asLong();
        long second = onlyParty(users.get(1)).get("myRequestId").asLong();

        mvc.perform(delete("/api/requests/" + first).session(users.get(0))).andExpect(status().isOk());
        mvc.perform(delete("/api/requests/" + second).session(users.get(1))).andExpect(status().isOk());

        assertThat(state(users.get(2)).get("parties")).isEmpty();
        assertThat(state(users.get(2)).get("latestRequest").get("status").asText()).isEqualTo("WAITING");
    }

    @Test
    void noShowIsRemovedAndArrivedRidersBoardWithNewPayer() throws Exception {
        List<MockHttpSession> users = threeRequests();
        demo.advance(5);
        long partyId = onlyParty(users.get(0)).get("id").asLong();
        mvc.perform(post("/api/parties/" + partyId + "/arrive").session(users.get(0))).andExpect(status().isOk());
        mvc.perform(post("/api/parties/" + partyId + "/arrive").session(users.get(1))).andExpect(status().isOk());

        demo.advance(30);

        Member noShow = members.findByEmail("demo-f3@daejin.ac.kr").orElseThrow();
        assertThat(noShow.getNoShowCount()).isEqualTo(1);
        assertThat(state(users.get(2)).get("latestRequest").get("status").asText()).isEqualTo("CANCELED");
        assertThat(state(users.get(2)).get("parties")).isEmpty();
        JsonNode party = onlyParty(users.get(1));
        assertThat(party.get("members")).hasSize(2);
        assertThat(party.get("payer").asBoolean()).isTrue();
        assertThat(party.get("canSettle").asBoolean()).isTrue();
        assertThat(state(users.get(0)).get("latestRequest").get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void unsubmittedSettlementEndsAfterTwentyFourHours() throws Exception {
        List<MockHttpSession> users = departedParty();
        assertThat(state(users.get(0)).get("parties")).hasSize(1);

        demo.advance(24 * 60 + 5);

        assertThat(state(users.get(0)).get("parties")).isEmpty();
        assertThat(state(users.get(0)).get("latestRequest").get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void amountCanBeCorrectedOnlyBeforeAnyoneSends() throws Exception {
        List<MockHttpSession> users = departedParty();
        MockHttpSession payer = payerOf(users);
        long partyId = onlyParty(payer).get("id").asLong();
        long total = onlyParty(payer).get("expectedTotal").asLong();
        submit(partyId, total, payer).andExpect(status().isOk());

        correct(partyId, total + 1000, payer).andExpect(status().isOk());
        JsonNode settlement = onlyParty(payer).get("settlement");
        assertThat(settlement.get("revision").asInt()).isEqualTo(1);
        JsonNode transfer = settlement.get("transfers").get(0);
        MockHttpSession sender = sessionOf(users, transfer.get("senderId").asLong());

        sent(transfer, 0, sender).andExpect(status().isConflict());
        sent(transfer, 1, sender).andExpect(status().isOk());
        correct(partyId, total, payer).andExpect(status().isConflict());
    }

    @Test
    void warningIsShownOverFifteenPercentButSettlementProceeds() throws Exception {
        List<MockHttpSession> users = departedParty();
        MockHttpSession payer = payerOf(users);
        JsonNode party = onlyParty(payer);
        long over = (long) (party.get("expectedTotal").asLong() * 1.2);

        submit(party.get("id").asLong(), over, payer).andExpect(status().isOk());

        assertThat(onlyParty(payer).get("settlement").get("warning").asBoolean()).isTrue();
    }

    @Test
    void negativePayerShareIsRejected() throws Exception {
        List<MockHttpSession> users = departedParty();
        MockHttpSession payer = payerOf(users);

        submit(onlyParty(payer).get("id").asLong(), 100, payer).andExpect(status().isBadRequest());
    }

    @Test
    void unsentTransferBlocksNewRequestAfterTwentyFourHoursUntilSent() throws Exception {
        List<MockHttpSession> users = departedParty();
        MockHttpSession payer = payerOf(users);
        JsonNode party = onlyParty(payer);
        submit(party.get("id").asLong(), party.get("expectedTotal").asLong(), payer).andExpect(status().isOk());
        JsonNode transfer = onlyParty(payer).get("settlement").get("transfers").get(0);
        MockHttpSession sender = sessionOf(users, transfer.get("senderId").asLong());

        demo.advance(24 * 60);

        assertThat(state(sender).get("restricted").asBoolean()).isTrue();
        createRequest(sender, POINTS[0]).andExpect(status().isConflict());
        sent(transfer, 0, sender).andExpect(status().isOk());
        assertThat(state(sender).get("restricted").asBoolean()).isFalse();
    }

    @Test
    void signupRequiresSchoolMailAndCodeBeforeLogin() throws Exception {
        String email = "new" + System.nanoTime() + "@daejin.ac.kr";
        String body = "{\"email\":\"%s\",\"password\":\"password1\",\"nickname\":\"새학생\",\"gender\":\"FEMALE\"}";
        String login = "{\"email\":\"" + email + "\",\"password\":\"password1\"}";

        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted("a@gmail.com"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted(email))).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isForbidden());

        String code = members.findByEmail(email).orElseThrow().getVerificationCode();
        mvc.perform(post("/api/auth/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"code\":\"000000x\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isOk());
    }

    @Test
    void receiptFileIsDeletedAfterSevenDaysButSettlementRemains() throws Exception {
        List<MockHttpSession> users = departedParty();
        MockHttpSession payer = payerOf(users);
        JsonNode party = onlyParty(payer);
        long partyId = party.get("id").asLong();
        long total = party.get("expectedTotal").asLong();
        submit(partyId, total, payer).andExpect(status().isOk());
        mvc.perform(get("/api/parties/" + partyId + "/receipt").session(payer)).andExpect(status().isOk());
        String path = settlements.findAll().get(0).getReceiptPath();
        assertThat(java.nio.file.Path.of(path)).exists();

        clock.moveTo(clock.now().plusSeconds(7 * 24 * 3600L + 60));
        demo.runNow();

        assertThat(java.nio.file.Path.of(path)).doesNotExist();
        mvc.perform(get("/api/parties/" + partyId + "/receipt").session(payer)).andExpect(status().isNotFound());
        JsonNode settlement = onlyParty(payer).get("settlement");
        assertThat(settlement.get("receiptAvailable").asBoolean()).isFalse();
        assertThat(settlement.get("paidAmount").asLong()).isEqualTo(total);
    }

    @Test
    void correctionReplacesReceiptFile() throws Exception {
        List<MockHttpSession> users = departedParty();
        MockHttpSession payer = payerOf(users);
        JsonNode party = onlyParty(payer);
        long partyId = party.get("id").asLong();
        submit(partyId, party.get("expectedTotal").asLong(), payer).andExpect(status().isOk());
        String oldPath = settlements.findAll().get(0).getReceiptPath();

        correct(partyId, party.get("expectedTotal").asLong() + 500, payer).andExpect(status().isOk());

        String newPath = settlements.findAll().get(0).getReceiptPath();
        assertThat(newPath).isNotEqualTo(oldPath);
        assertThat(java.nio.file.Path.of(oldPath)).doesNotExist();
        assertThat(java.nio.file.Path.of(newPath)).exists();
    }

    @Test
    void rejectsSvgReceiptAndHugeAmount() throws Exception {
        List<MockHttpSession> users = departedParty();
        MockHttpSession payer = payerOf(users);
        long partyId = onlyParty(payer).get("id").asLong();
        MockMultipartFile svg = new MockMultipartFile("receipt", "r.svg", "image/svg+xml", "<svg/>".getBytes());

        mvc.perform(multipart("/api/parties/" + partyId + "/settlement").file(svg)
                .param("amount", "20000").session(payer)).andExpect(status().isBadRequest());
        submit(partyId, 1_000_000_000_000_000L, payer).andExpect(status().isBadRequest());
    }

    @Test
    void cannotMarkArrivedAfterDeparture() throws Exception {
        List<MockHttpSession> users = threeRequests();
        demo.advance(5);
        JsonNode party = onlyParty(users.get(0));
        clock.moveTo(Instant.parse(party.get("departureAt").asText()).plusSeconds(1));

        mvc.perform(post("/api/parties/" + party.get("id").asLong() + "/arrive").session(users.get(0)))
                .andExpect(status().isConflict());
    }

    @Test
    void outsiderCannotSeeOrChangeAParty() throws Exception {
        List<MockHttpSession> users = departedParty();
        MockHttpSession payer = payerOf(users);
        JsonNode party = onlyParty(payer);
        long partyId = party.get("id").asLong();
        submit(partyId, party.get("expectedTotal").asLong(), payer).andExpect(status().isOk());
        JsonNode transfer = onlyParty(payer).get("settlement").get("transfers").get(0);
        MockHttpSession outsider = login("demo-m1");

        mvc.perform(get("/api/parties/" + partyId).session(outsider)).andExpect(status().isForbidden());
        mvc.perform(get("/api/parties/" + partyId + "/receipt").session(outsider)).andExpect(status().isForbidden());
        submit(partyId, 1000, outsider).andExpect(status().isForbidden());
        sent(transfer, 0, outsider).andExpect(status().isForbidden());
        mvc.perform(get("/api/state")).andExpect(status().isUnauthorized());
    }

    @Test
    void demoActMarksOnlySampleMembers() throws Exception {
        List<MockHttpSession> users = threeRequests();
        demo.advance(5);
        MockHttpSession me = users.get(0);
        long partyId = onlyParty(me).get("id").asLong();

        mvc.perform(post("/api/demo/act").session(me)).andExpect(status().isOk());

        JsonNode party = onlyParty(me);
        assertThat(party.get("canArrive").asBoolean()).isTrue();
        long arrived = 0;
        for (JsonNode m : party.get("members")) {
            arrived += m.get("arrived").asBoolean() ? 1 : 0;
        }
        assertThat(arrived).isEqualTo(2);
        mvc.perform(post("/api/parties/" + partyId + "/arrive").session(me)).andExpect(status().isOk());
        demo.advance(30);
        assertThat(state(me).get("parties")).hasSize(1);
    }

    @Test
    void validatesRequestWindow() throws Exception {
        MockHttpSession user = login("demo-f1");
        Instant start = AppClock.nextSlotAfter(clock.now()).plusSeconds(3600);

        postRequest(user, start.plusSeconds(60), start.plusSeconds(1800), POINTS[0]).andExpect(status().isBadRequest());
        postRequest(user, start, start.plusSeconds(3900), POINTS[0]).andExpect(status().isBadRequest());
        Instant soon = AppClock.nextSlotAfter(clock.now());
        postRequest(user, soon, soon.plusSeconds(600), POINTS[0]).andExpect(status().isBadRequest());
    }

    @Test
    void oneActiveRequestPerMemberUnderConcurrency() throws Exception {
        MockHttpSession user = login("demo-f1");
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> createRequest(user, POINTS[0]).andReturn().getResponse().getStatus());
        }
        int ok = 0;
        for (Future<Integer> result : pool.invokeAll(tasks)) {
            if (result.get() == 200) {
                ok++;
            }
        }
        pool.shutdown();

        assertThat(ok).isEqualTo(1);
    }

    @Test
    void concurrentCancelAndMatchingNeverDoubleAssign() throws Exception {
        List<MockHttpSession> users = threeRequests();
        long firstRequest = state(users.get(0)).get("latestRequest").get("id").asLong();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Callable<Integer>> tasks = new ArrayList<>();
        clock.moveTo(AppClock.nextSlotAfter(clock.now()).plusSeconds(1));
        for (int i = 0; i < 9; i++) {
            tasks.add(() -> {
                demo.runNow();
                return 0;
            });
        }
        tasks.add(() -> mvc.perform(delete("/api/requests/" + firstRequest).session(users.get(0)))
                .andReturn().getResponse().getStatus());
        for (Future<Integer> result : pool.invokeAll(tasks)) {
            result.get();
        }
        pool.shutdown();

        for (MockHttpSession user : users) {
            assertThat(state(user).get("parties").size()).isLessThanOrEqualTo(1);
        }
    }

    private List<MockHttpSession> departedParty() throws Exception {
        List<MockHttpSession> users = threeRequests();
        demo.advance(5);
        long partyId = onlyParty(users.get(0)).get("id").asLong();
        for (MockHttpSession user : users) {
            mvc.perform(post("/api/parties/" + partyId + "/arrive").session(user)).andExpect(status().isOk());
        }
        demo.advance(30);
        return users;
    }

    private List<MockHttpSession> threeRequests() throws Exception {
        List<MockHttpSession> users = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            MockHttpSession user = login("demo-f" + (i + 1));
            createRequest(user, POINTS[i]).andExpect(status().isOk());
            users.add(user);
        }
        return users;
    }

    private org.springframework.test.web.servlet.ResultActions createRequest(MockHttpSession user, double[] point) throws Exception {
        Instant start = AppClock.nextSlotAfter(clock.now()).plusSeconds(1800);
        return postRequest(user, start, start.plusSeconds(3600), point);
    }

    private org.springframework.test.web.servlet.ResultActions postRequest(MockHttpSession user, Instant start, Instant end, double[] point) throws Exception {
        String body = json.writeValueAsString(Map.of("hubId", hubId, "windowStart", start.toString(),
                "windowEnd", end.toString(), "dropoffName", "샘플", "dropoffLat", point[0], "dropoffLon", point[1]));
        return mvc.perform(post("/api/requests").session(user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private org.springframework.test.web.servlet.ResultActions submit(long partyId, long amount, MockHttpSession user) throws Exception {
        MockMultipartFile receipt = new MockMultipartFile("receipt", "r.png", "image/png", new byte[] {1, 2, 3});
        return mvc.perform(multipart("/api/parties/" + partyId + "/settlement").file(receipt)
                .param("amount", String.valueOf(amount)).session(user));
    }

    private org.springframework.test.web.servlet.ResultActions correct(long partyId, long amount, MockHttpSession user) throws Exception {
        MockMultipartFile receipt = new MockMultipartFile("receipt", "n.png", "image/png", new byte[] {4, 5, 6});
        return mvc.perform(multipart("/api/parties/" + partyId + "/settlement/correction").file(receipt)
                .param("amount", String.valueOf(amount)).session(user));
    }

    private org.springframework.test.web.servlet.ResultActions sent(JsonNode transfer, int revision, MockHttpSession user) throws Exception {
        return mvc.perform(post("/api/transfers/" + transfer.get("id").asLong() + "/sent").session(user)
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":" + revision + "}"));
    }

    private MockHttpSession login(String id) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + id + "@daejin.ac.kr\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk());
        return session;
    }

    private JsonNode state(MockHttpSession user) throws Exception {
        return read(mvc.perform(get("/api/state").session(user)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode onlyParty(MockHttpSession user) throws Exception {
        JsonNode list = state(user).get("parties");
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    private MockHttpSession payerOf(List<MockHttpSession> users) throws Exception {
        for (MockHttpSession user : users) {
            if (onlyParty(user).get("payer").asBoolean()) {
                return user;
            }
        }
        throw new AssertionError("no payer");
    }

    private MockHttpSession sessionOf(List<MockHttpSession> users, long memberId) throws Exception {
        for (MockHttpSession user : users) {
            JsonNode me = read(mvc.perform(get("/api/me").session(user)).andReturn().getResponse().getContentAsString());
            if (me.get("id").asLong() == memberId) {
                return user;
            }
        }
        throw new AssertionError("no session");
    }

    private JsonNode read(String body) throws Exception {
        return json.readTree(body);
    }
}
