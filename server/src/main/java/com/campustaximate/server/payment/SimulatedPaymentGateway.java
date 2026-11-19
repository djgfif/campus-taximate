package com.campustaximate.server.payment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 키가 없을 때 쓰는 모의 장부. 실제 결제나 환불은 하지 않는다. */
public class SimulatedPaymentGateway implements PaymentGateway {
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final Map<String, Payment> operations = new ConcurrentHashMap<>();
    @Override public synchronized Payment confirm(String key, String order, long amount, String idempotency) {
        Payment old = operations.get(idempotency);
        if (old != null) return old;
        Payment payment = new Payment(key, order, amount, 0, true);
        payments.put(key, payment);
        operations.put(idempotency, payment);
        return payment;
    }
    @Override public synchronized Payment cancel(String key, long amount, String reason, String idempotency) {
        Payment old = operations.get(idempotency);
        if (old != null) return old;
        Payment previous = get(key);
        if (previous.canceledAmount() + amount > previous.amount()) throw new Unavailable("모의 취소 잔액 부족");
        Payment payment = new Payment(key, previous.orderId(), previous.amount(), previous.canceledAmount() + amount, true);
        payments.put(key, payment);
        operations.put(idempotency, payment);
        return payment;
    }
    @Override public Payment get(String key) {
        Payment result = payments.get(key);
        if (result == null) throw new Unavailable("모의 결제 미조회");
        return result;
    }
    @Override public Payment getByOrderId(String order) {
        return payments.values().stream().filter(p -> p.orderId().equals(order)).findFirst()
                .orElseThrow(() -> new Unavailable("모의 주문 미조회"));
    }
    @Override public boolean simulated() { return true; }
}
