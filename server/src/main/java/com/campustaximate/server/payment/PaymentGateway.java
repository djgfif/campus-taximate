package com.campustaximate.server.payment;

/** 승인·취소 결과를 조회해 응답 유실을 복구하는 결제 포트. */
public interface PaymentGateway {
    enum Resolution { APPROVED, FAILED, PENDING }
    record Payment(String paymentKey, String orderId, long amount, long canceledAmount, boolean approved, Resolution resolution) {
        public Payment(String key,String order,long amount,long canceled,boolean approved) {
            this(key,order,amount,canceled,approved,approved?Resolution.APPROVED:Resolution.PENDING);
        }
    }
    Payment confirm(String paymentKey, String orderId, long amount, String idempotencyKey);
    Payment cancel(String paymentKey, long cancelAmount, String reason, String idempotencyKey);
    Payment get(String paymentKey);
    Payment getByOrderId(String orderId);
    default boolean simulated() { return false; }
    /** 결제사가 요청을 확정적으로 거절함(없는 결제, 카드 거절 등). 다시 보내도 결과가 같으므로 재시도하지 않는다. */
    class Rejected extends RuntimeException {
        private final String code;
        public Rejected(String code, Throwable cause) { super("결제사가 요청을 거절했습니다: " + code, cause); this.code = code; }
        public String code() { return code; }
    }
    class Unavailable extends RuntimeException {
        public Unavailable(String message) { super(message); }
        public Unavailable(String message, Throwable cause) { super(message, cause); }
    }
}
