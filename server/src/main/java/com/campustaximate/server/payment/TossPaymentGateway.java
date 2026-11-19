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
import org.springframework.web.client.RestClientResponseException;

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
    /** 토스 오류 본문 {"code": "...", "message": "..."}의 code. 읽지 못하면 HTTP 상태를 돌려준다. */
    private static String errorCode(RestClientResponseException e) {
        try {
            JsonNode body = e.getResponseBodyAs(JsonNode.class);
            if (body != null && body.hasNonNull("code")) return body.path("code").asText();
        } catch (RuntimeException ignored) { /* 본문이 JSON이 아니면 상태 코드만 쓴다. */ }
        return "HTTP_" + e.getStatusCode().value();
    }
    /**
     * 다시 보내도 같은 답이 오는 거절인지 가른다. 4xx는 거절로 보되, 인증·시간 초과·충돌·요청 과다와
     * "이미 처리됨", "처리 중", 결제사 일시 오류는 조회·재시도로 풀어야 하므로 뺀다.
     */
    static boolean definite(int status, String code) {
        if (status < 400 || status >= 500) return false;
        if (status == 401 || status == 408 || status == 409 || status == 429) return false;
        return !java.util.Set.of("ALREADY_PROCESSED_PAYMENT", "PROVIDER_ERROR", "IDEMPOTENT_REQUEST_PROCESSING",
                "UNAUTHORIZED_KEY", "FORBIDDEN_REQUEST").contains(code);
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
        } catch (RestClientResponseException e) {
            String code = errorCode(e);
            if (definite(e.getStatusCode().value(), code)) throw new Rejected(code, e);
            throw new Unavailable("토스 결제 응답을 확인하지 못했습니다.", e);
        } catch (RuntimeException e) { throw new Unavailable("토스 결제 응답을 확인하지 못했습니다.", e); }
    }
}
