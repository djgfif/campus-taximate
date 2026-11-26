package com.campustaximate.server;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.campustaximate.matching.Direction;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.PartyStatus;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.repository.*;
import com.campustaximate.server.service.LoginTokenService;
import com.campustaximate.server.service.PaymentMethodService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:postpaid-payment-guard;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@TestPropertySource(properties = "app.payment.flow-version=3")
class PostpaidPaymentGuardTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private AppClock clock;
    @Autowired private LoginTokenService tokens;
    @Autowired private PaymentMethodService methods;
    @Autowired private MemberRepository members;
    @Autowired private HubRepository hubs;
    @Autowired private PartyRepository parties;
    @Autowired private RideRequestRepository requests;

    @Test
    void requestNeedsAnActivePaymentMethodAndPartyViewExposesItsStoredFlowVersion() throws Exception {
        var member = members.findByEmail("demo-f1@daejin.ac.kr").orElseThrow();
        long memberId = member.getId();
        var actor = new MockHttpSession();
        AuthenticatedTestSession.authenticate(actor, memberId, tokens);
        methods.delete(memberId, methods.list(memberId).getFirst().id());
        var hub = hubs.findAll().getFirst();
        Instant departure = AppClock.nextSlotAfter(clock.now()).plusSeconds(1800);
        String body = json.writeValueAsString(Map.of("hubId", hub.getId(), "direction", "FROM_HUB",
                "windowStart", departure, "windowEnd", departure.plusSeconds(3600), "pointName", "도착지",
                "pointLat", 37.843, "pointLon", 127.150));

        mvc.perform(post("/api/requests").session(actor).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_METHOD_REQUIRED"));
        mvc.perform(post("/api/payment-methods").session(actor).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"SIMULATED","billingKey":"test-billing-guard","cardLabel":"테스트카드 ****1234"}
                                """))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/requests").session(actor).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        // The foundation exposes the party's stored version and cancellation deadline.
        Party party = new Party(Direction.FROM_HUB, hub, null, member, departure, 5_000);
        party.setPaymentFlowVersion(3);
        party.setStatus(PartyStatus.READY);
        party.setPrepayDeadlineAt(departure.minusSeconds(1200));
        party.setFreeCancelUntil(departure.minusSeconds(600));
        parties.save(party);
        var request = requests.findByActiveMemberId(memberId).orElseThrow();
        request.setParty(party);
        request.setStatus(RequestStatus.MATCHED);
        request.setRideOrder(1);
        request.setExpectedShare(5_000L);
        requests.save(request);
        mvc.perform(get("/api/parties/{id}", party.getId()).session(actor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentFlowVersion").value(3))
                .andExpect(jsonPath("$.freeCancelUntil").value(party.freeCancelUntil().toString()));
    }
}
