package com.campustaximate.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.campustaximate.matching.Direction;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.ApiExceptionHandler;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.PartyStatus;
import com.campustaximate.server.domain.PaymentOrder;
import com.campustaximate.server.domain.PaymentPurpose;
import com.campustaximate.server.domain.PaymentStatus;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.repository.*;
import com.campustaximate.server.service.DataSeeder;
import com.campustaximate.server.service.LoginTokenService;
import com.campustaximate.server.service.PaymentMethodService;
import com.campustaximate.server.web.PaymentMethodController;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Transactional
class PaymentMethodServiceTest {
    private static final String PATH = "/api/payment-methods";
    private static final String BILLING_KEY = "fixture-billing-token";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private AppClock clock;
    @Autowired private LoginTokenService tokens;
    @Autowired private MemberRepository members;
    @Autowired private HubRepository hubs;
    @Autowired private PartyRepository parties;
    @Autowired private RideRequestRepository requests;
    @Autowired private PaymentOrderRepository orders;
    @Autowired private PaymentMethodRepository methods;
    @Autowired private PaymentMethodService service;
    @Autowired private DataSeeder seeder;

    @ParameterizedTest
    @ValueSource(strings = {"4111-1111-1111-1234", "4111.1111.1111.1234", "4111\t1111\t1111\t1234"})
    void registrationMasksCardLabelAndEncryptsTheBillingKeyAndOwnerCanDelete(String cardNumber) throws Exception {
        long owner = memberId("demo-f1");
        MockHttpSession actor = session(owner);
        String response = mvc.perform(post(PATH).session(actor).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("provider", "TOSS", "billingKey", BILLING_KEY,
                                "cardLabel", "신한카드 " + cardNumber))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("TOSS"))
                .andExpect(jsonPath("$.cardLabel").value("신한카드 ****1234"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.registeredAt").exists())
                .andExpect(jsonPath("$.billingKey").doesNotHaveJsonPath())
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(response).get("id").asLong();
        assertThat(jdbc.queryForObject("select billing_key from payment_method where id = ?", String.class, id))
                .isNotBlank().doesNotContain(BILLING_KEY, cardNumber);
        entities.clear();
        assertThat(methods.findById(id).orElseThrow().getBillingKey()).isEqualTo(BILLING_KEY);

        mvc.perform(get(PATH).session(actor)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].cardLabel").value("신한카드 ****1234"))
                .andExpect(jsonPath("$[*].billingKey").doesNotHaveJsonPath());
        MockHttpSession outsider = session(memberId("demo-f2"));
        mvc.perform(get(PATH).session(outsider)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").isEmpty());
        mvc.perform(delete(PATH + "/{id}", id).session(outsider)).andExpect(status().isNotFound());

        mvc.perform(delete(PATH + "/{id}", id).session(actor)).andExpect(status().isNoContent());
        mvc.perform(get(PATH).session(actor)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").isEmpty());
        var deleted = methods.findById(id).orElseThrow();
        assertThat(deleted.isActive()).isFalse();
        assertThat(deleted.getDeletedAt()).isNotNull();
    }

    @Test
    void registrationRejectsMissingBillingKeyOrCardNumbersInKey() throws Exception {
        MockHttpSession actor = session(memberId("demo-f1"));
        for (String input : new String[]{
                """
                {"provider":"TOSS","cardLabel":"신한카드 ****1234"}
                """,
                """
                {"provider":"TOSS","billingKey":"4111.1111.1111.1234","cardLabel":"신한카드 ****1234"}
                """,
                """
                {"provider":"TOSS","billingKey":"card_4111-1111-1111-1234","cardLabel":"신한카드 ****1234"}
                """,
                """
                {"provider":"TOSS","billingKey":"card_4111111111111111","cardLabel":"신한카드 ****1234"}
                """
        }) {
            mvc.perform(post(PATH).session(actor).contentType(MediaType.APPLICATION_JSON).content(input))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_METHOD"));
        }
    }

    @Test
    void realPaymentModeRejectsSimulatedRegistration() {
        var realPaymentService = new PaymentMethodService(methods, members, orders, requests, clock, false);

        assertThatThrownBy(() -> realPaymentService.register(memberId("demo-f1"), "SIMULATED", BILLING_KEY, "테스트카드"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getCode()).isEqualTo("INVALID_PAYMENT_METHOD");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"toss", "TOSS ", "simulated", "OTHER"})
    void registrationRequiresAnExactSupportedProvider(String provider) throws Exception {
        mvc.perform(post(PATH).session(session(memberId("demo-f1"))).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("provider", provider, "billingKey", BILLING_KEY,
                                "cardLabel", "테스트카드"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_METHOD"));
    }

    @Test
    void controllerReturnsUnauthorizedWhenSessionMemberIdIsMissingOrHasTheWrongType() throws Exception {
        MockMvc controller = standaloneSetup(new PaymentMethodController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var actor = new MockHttpSession();
        controller.perform(get(PATH).session(actor)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        actor.setAttribute("memberId", "1");
        controller.perform(get(PATH).session(actor)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void activeRequestRequiresOnePaymentMethodButAllowsDeletingAnExtraMethod() throws Exception {
        long memberId = memberId("demo-f1");
        var member = members.findById(memberId).orElseThrow();
        long seededMethodId = service.list(memberId).getFirst().id();
        service.delete(memberId, seededMethodId);
        long firstMethodId = service.register(memberId, "TOSS", BILLING_KEY, "테스트카드").id();

        RideRequest request = rideRequest(member);
        request.setActiveMemberId(memberId);
        requests.save(request);

        MockHttpSession actor = session(memberId);
        expectActiveRideDeletionConflict(actor, firstMethodId);

        long extraMethodId = service.register(memberId, "TOSS_BILLING", "extra-billing-token", "추가카드").id();
        mvc.perform(delete(PATH + "/{id}", extraMethodId).session(actor)).andExpect(status().isNoContent());
        expectActiveRideDeletionConflict(actor, firstMethodId);
        assertThat(service.list(memberId)).extracting(PaymentMethodService.PaymentMethodView::id)
                .containsExactly(firstMethodId);
    }

    @ParameterizedTest
    @EnumSource(value = PartyStatus.class, names = {"PREPAYING", "READY", "PAYER_PENDING", "RIDE_UNCONFIRMED",
            "AWAITING_FARE", "OBJECTION_WINDOW", "REVIEW_PENDING"})
    void openPartyRequiresTheLastPaymentMethodEvenWithoutAnActiveRequest(PartyStatus status) throws Exception {
        long memberId = memberId("demo-f1");
        var member = members.findById(memberId).orElseThrow();
        long methodId = service.list(memberId).getFirst().id();
        RideRequest request = rideRequest(member);
        Party party = new Party(Direction.FROM_HUB, request.getHub(), null, member, clock.now(), 5_000);
        party.setStatus(status);
        request.setParty(parties.save(party));
        request.setStatus(RequestStatus.MATCHED);
        requests.save(request);

        assertThat(requests.findByActiveMemberId(memberId)).isEmpty();
        expectActiveRideDeletionConflict(session(memberId), methodId);
        assertThat(service.list(memberId)).extracting(PaymentMethodService.PaymentMethodView::id)
                .containsExactly(methodId);
    }

    @ParameterizedTest
    @CsvSource({"POSTPAID,REQUIRED", "COMPENSATION,OVERDUE", "ADDITIONAL,REQUIRED"})
    void unpaidOrderBlocksDeletionUntilPaid(PaymentPurpose purpose, PaymentStatus status) throws Exception {
        var member = members.findById(memberId("demo-f1")).orElseThrow();
        long memberId = member.getId();
        long methodId = service.list(memberId).getFirst().id();
        var now = clock.now().truncatedTo(ChronoUnit.SECONDS);
        Party party = parties.save(new Party(Direction.FROM_HUB, hubs.findAll().getFirst(), null, member, now, 5_000));
        PaymentOrder order = new PaymentOrder();
        order.setOrderId("unpaid-fixture");
        order.setMemberId(memberId);
        order.setPartyId(party.getId());
        order.setPurpose(purpose);
        order.setStatus(status);
        order.setAmount(5_000);
        order.setCreatedAt(now);
        order.setDeadlineAt(now);
        orders.save(order);
        assertThat(orders.hasUnpaidAdditional(memberId, now.minusSeconds(1))).isFalse();
        assertThat(orders.hasUnpaidAdditional(memberId, now)).isTrue();

        MockHttpSession actor = session(memberId);
        mvc.perform(delete(PATH + "/{id}", methodId).session(actor))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_OVERDUE"));
        assertThat(service.list(memberId)).extracting(PaymentMethodService.PaymentMethodView::id).contains(methodId);

        order.setStatus(PaymentStatus.APPROVED);
        assertThat(orders.hasUnpaidAdditional(memberId, now)).isFalse();
        mvc.perform(delete(PATH + "/{id}", methodId).session(actor)).andExpect(status().isNoContent());
    }

    @Test
    void allNineDemoAccountsHaveOneSimulatedMethodAfterReseeding() {
        seeder.run(new DefaultApplicationArguments());
        for (String account : new String[]{"demo-f1", "demo-f2", "demo-f3", "demo-f4",
                "demo-m1", "demo-m2", "demo-m3", "demo-m4", "demo-admin"}) {
            assertThat(service.list(memberId(account))).singleElement().satisfies(method -> {
                assertThat(method.provider()).isEqualTo("SIMULATED");
                assertThat(method.active()).isTrue();
                assertThat(method.cardLabel()).isEqualTo("신한카드 ****1234");
            });
        }
    }

    private long memberId(String name) {
        return members.findByEmail(name + "@daejin.ac.kr").orElseThrow().getId();
    }

    private RideRequest rideRequest(Member member) {
        RideRequest request = new RideRequest();
        request.setMember(member);
        request.setDirection(Direction.FROM_HUB);
        request.setHub(hubs.findAll().getFirst());
        request.setPointName("도착지");
        return request;
    }

    private void expectActiveRideDeletionConflict(MockHttpSession actor, long methodId) throws Exception {
        mvc.perform(delete(PATH + "/{id}", methodId).session(actor))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_RIDE_REQUIRES_PAYMENT_METHOD"));
    }

    private MockHttpSession session(long memberId) {
        var session = new MockHttpSession();
        AuthenticatedTestSession.authenticate(session, memberId, tokens);
        return session;
    }
}
