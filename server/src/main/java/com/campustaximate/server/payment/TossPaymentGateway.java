package com.campustaximate.server.payment;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public class TossPaymentGateway implements PaymentGateway {
    private final RestClient client;
    public TossPaymentGateway(RestClient.Builder builder, String baseUrl, String secret, Duration timeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        client = builder.requestFactory(factory).baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, authorization(secret)).build();
    }
    static String authorization(String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((secret + ":").getBytes(StandardCharsets.UTF_8));
    }
    /** 실제 네트워크를 사용하지 않는 전송 계층 테스트용. */
    TossPaymentGateway(RestClient client) { this.client = client; }
    @Override public Payment confirm(String key, String order, long amount, String idempotency) {
        return call(() -> client.post().uri("/v1/payments/confirm").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotency)
                .body(Map.of("paymentKey", key, "orderId", order, "amount", amount)).retrieve().body(JsonNode.class));
    }
    @Override public Payment cancel(String key, long amount, String reason, String idempotency) {
        return call(() -> client.post().uri("/v1/payments/{key}/cancel", key).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotency)
                .body(Map.of("cancelAmount", amount, "cancelReason", reason)).retrieve().body(JsonNode.class));
    }
    @Override public Payment get(String key) {
        return call(() -> client.get().uri("/v1/payments/{key}", key).retrieve().body(JsonNode.class));
    }
    @Override public Payment getByOrderId(String order) {
        return call(() -> client.get().uri("/v1/payments/orders/{order}", order).retrieve().body(JsonNode.class));
    }
    private Payment call(Supplier<JsonNode> lookup) {
        try {
            JsonNode node = lookup.get();
            if (node == null || !node.hasNonNull("paymentKey") || !node.hasNonNull("orderId")
                    || !node.path("totalAmount").canConvertToLong()) throw new Unavailable("결제 응답 필수 값 누락");
            long canceled = 0;
            for (JsonNode cancel : node.path("cancels")) canceled = Math.addExact(canceled, cancel.path("cancelAmount").asLong());
            String status = node.path("status").asText();
            return new Payment(node.path("paymentKey").asText(), node.path("orderId").asText(),
                    node.path("totalAmount").asLong(), canceled,
                    status.equals("DONE") || status.equals("PARTIAL_CANCELED") || status.equals("CANCELED"),
                    status.equals("ABORTED") || status.equals("EXPIRED") ? Resolution.FAILED
                        : status.equals("DONE") || status.equals("PARTIAL_CANCELED") || status.equals("CANCELED") ? Resolution.APPROVED : Resolution.PENDING);
        } catch (Unavailable e) { throw e;
        } catch (RuntimeException e) { throw new Unavailable("토스 결제 응답을 확인하지 못했습니다.", e); }
    }
}
