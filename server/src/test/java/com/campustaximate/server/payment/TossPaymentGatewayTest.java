package com.campustaximate.server.payment;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 전송 계층은 모두 메모리 모의 서버다. 실제 토스 호출은 하지 않는다. */
class TossPaymentGatewayTest {
    private static final String RESULT="{\"paymentKey\":\"pk\",\"orderId\":\"order\",\"totalAmount\":3680,\"status\":\"DONE\",\"cancels\":[]}";
    private final RestClient.Builder builder=RestClient.builder().baseUrl("https://mock.invalid")
            .defaultHeader(HttpHeaders.AUTHORIZATION,TossPaymentGateway.authorization("test_secret"));
    private final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    private final TossPaymentGateway gateway=new TossPaymentGateway(builder.build());
    @Test void confirmUsesBasicAuthenticationAndIdempotencyKey() {
        assertThat(TossPaymentGateway.authorization("test_secret")).isEqualTo("Basic dGVzdF9zZWNyZXQ6");
        server.expect(requestTo("https://mock.invalid/v1/payments/confirm")).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION,"Basic dGVzdF9zZWNyZXQ6"))
                .andExpect(header("Idempotency-Key","confirm-key"))
                .andExpect(content().json("{\"paymentKey\":\"pk\",\"orderId\":\"order\",\"amount\":3680}"))
                .andRespond(withSuccess(RESULT,MediaType.APPLICATION_JSON));
        assertThat(gateway.confirm("pk","order",3680,"confirm-key").amount()).isEqualTo(3680); server.verify();
    }
    @Test void cancelPostsPartialAmountAndParsesCumulativeCancellation() {
        server.expect(requestTo("https://mock.invalid/v1/payments/pk/cancel")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key","cancel-key"))
                .andExpect(content().json("{\"cancelAmount\":480,\"cancelReason\":\"정산 차액\"}"))
                .andRespond(withSuccess(RESULT.replace("DONE","PARTIAL_CANCELED").replace("[]","[{\"cancelAmount\":480}]"),MediaType.APPLICATION_JSON));
        assertThat(gateway.cancel("pk",480,"정산 차액","cancel-key").canceledAmount()).isEqualTo(480); server.verify();
    }
    @Test void queriesByPaymentKeyAndOrderId() {
        server.expect(requestTo("https://mock.invalid/v1/payments/pk")).andRespond(withSuccess(RESULT,MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://mock.invalid/v1/payments/orders/order")).andRespond(withSuccess(RESULT,MediaType.APPLICATION_JSON));
        assertThat(gateway.get("pk").approved()).isTrue(); assertThat(gateway.getByOrderId("order").orderId()).isEqualTo("order"); server.verify();
    }
    @Test void definiteRejectionIsNotRetried() {
        server.expect(requestTo("https://mock.invalid/v1/payments/confirm")).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"NOT_FOUND_PAYMENT\",\"message\":\"none\"}"));
        assertThatThrownBy(()->gateway.confirm("pk","order",3680,"k")).isInstanceOfSatisfying(PaymentGateway.Rejected.class,
                e->assertThat(e.code()).isEqualTo("NOT_FOUND_PAYMENT"));server.verify();
    }
    @Test void alreadyProcessedAndProviderErrorsStayRecoverable() {
        assertThat(TossPaymentGateway.definite(400,"ALREADY_PROCESSED_PAYMENT")).isFalse();
        assertThat(TossPaymentGateway.definite(400,"PROVIDER_ERROR")).isFalse();
        assertThat(TossPaymentGateway.definite(401,"UNAUTHORIZED_KEY")).isFalse();
        assertThat(TossPaymentGateway.definite(409,"IDEMPOTENT_REQUEST_PROCESSING")).isFalse();
        assertThat(TossPaymentGateway.definite(500,"FAILED_INTERNAL_SYSTEM_PROCESSING")).isFalse();
        assertThat(TossPaymentGateway.definite(403,"REJECT_CARD_PAYMENT")).isTrue();
        assertThat(TossPaymentGateway.definite(404,"NOT_FOUND_PAYMENT_SESSION")).isTrue();
    }
    @Test void failedApiBecomesRecoverableUnavailable() {
        server.expect(anything()).andRespond(withServerError());
        assertThatThrownBy(()->gateway.get("pk")).isInstanceOf(PaymentGateway.Unavailable.class);server.verify();
    }

    @Test void lookupDistinguishesTerminalFailureFromProcessing() {
        server.expect(requestTo("https://mock.invalid/v1/payments/pk")).andRespond(withSuccess(RESULT.replace("DONE","IN_PROGRESS"),MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://mock.invalid/v1/payments/pk")).andRespond(withSuccess(RESULT.replace("DONE","ABORTED"),MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://mock.invalid/v1/payments/pk")).andRespond(withSuccess(RESULT.replace("DONE","EXPIRED"),MediaType.APPLICATION_JSON));
        assertThat(gateway.get("pk").resolution()).isEqualTo(PaymentGateway.Resolution.PENDING);
        assertThat(gateway.get("pk").resolution()).isEqualTo(PaymentGateway.Resolution.FAILED);
        assertThat(gateway.get("pk").resolution()).isEqualTo(PaymentGateway.Resolution.FAILED);
        server.verify();
    }
}
