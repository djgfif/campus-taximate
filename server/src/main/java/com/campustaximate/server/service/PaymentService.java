package com.campustaximate.server.service;

import com.campustaximate.server.common.*;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import com.campustaximate.server.payment.PaymentGateway;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 주문 금액은 서버가 정한다. 네트워크 호출 전 작업을 커밋해 응답 유실도 복구한다. */
@Service
@RequiredArgsConstructor
public class PaymentService {
    private final PaymentOrderRepository orders;
    private final PaymentOperationRepository operations;
    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final PayoutLedgerRepository payouts;
    private final PaymentGateway gateway;
    private final AppClock clock;
    private final PartyChangeService partyChanges;
    private final PartyMemberTermRepository terms;
    private final PlatformTransactionManager transactionManager;

    public record OrderView(String orderId, long amount, PaymentPurpose purpose, PaymentStatus status,
                            Instant deadlineAt, long approvedAmount, long refundedAmount, long refundRequestedAmount, boolean simulated) { }
    private record Work(long operationId, long orderId, PaymentOperationType type, String key,
                        String paymentKey, String externalOrder, long amount, long orderAmount,
                        long targetRefund, String reason, int attempts) { }
    public boolean simulated() { return gateway.simulated(); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }

    public static long prepayAmount(long share) { return Math.addExact(Math.multiplyExact(share, 115), 99) / 100; }
    public static Instant prepayDeadline(Instant confirmed, Instant departure, boolean instant) {
        Instant minimum = confirmed.plusSeconds(300);
        if (instant) return minimum;
        Instant normal = confirmed.plusSeconds(3600);
        if (departure.minusSeconds(1200).isBefore(normal)) normal = departure.minusSeconds(1200);
        return normal.isBefore(minimum) ? minimum : normal;
    }

    @Transactional
    public void preparePrepayments(Party party, List<RideRequest> riders, Instant slot) {
        party.setPaymentFlowVersion(2);
        party.setConfirmedAt(slot);
        party.setPrepayDeadlineAt(prepayDeadline(slot, party.getDepartureAt(),
                riders.stream().anyMatch(r -> r.getMode() == RequestMode.INSTANT)));
        party.setStatus(PartyStatus.PREPAYING);
        for (RideRequest rider : riders) {
            if (!rider.getMember().getId().equals(party.getPayer().getId())) {
                createObligation(party.getId(), rider.getMember().getId(), rider.getId(), null,
                        PaymentPurpose.PREPAY, prepayAmount(rider.getExpectedShare()), party.getPrepayDeadlineAt());
            }
        }
        partyChanges.capture(party, riders, false);
    }

    /** 호출자는 일행 잠금을 먼저 잡는다. */
    public PaymentOrder createObligation(long partyId, long memberId, Long requestId, Long settlementId,
                                         PaymentPurpose purpose, long amount, Instant deadline) {
        PaymentOrder existing = orders.findByPartyIdAndMemberIdAndPurpose(partyId, memberId, purpose).orElse(null);
        if (existing != null) return existing;
        PaymentOrder order = new PaymentOrder();
        order.setPartyId(partyId); order.setMemberId(memberId); order.setRequestId(requestId); order.setSettlementId(settlementId);
        order.setPurpose(purpose); order.setAmount(amount); order.setDeadlineAt(deadline); order.setCreatedAt(clock.now());
        order.setOrderId("ctm_" + UUID.randomUUID());
        if (amount == 0) { order.setStatus(PaymentStatus.APPROVED); order.setApprovedAt(clock.now()); }
        return orders.save(order);
    }

    @Transactional
    public OrderView order(long memberId, long partyId, PaymentPurpose purpose) {
        Party party = parties.findByIdForUpdate(partyId).orElseThrow(() -> ApiException.notFound("없는 일행입니다."));
        if (!party.paymentFlow()) throw ApiException.conflict("LEGACY_PARTY", "기존 일행에는 결제 주문이 없습니다.");
        PaymentOrder order = orders.findByPartyIdAndMemberIdAndPurpose(partyId, memberId, purpose)
                .orElseThrow(() -> ApiException.notFound("본인의 결제 주문이 없습니다."));
        return view(order);
    }

    @Transactional(readOnly = true)
    public OrderView get(long memberId, String orderId) {
        PaymentOrder order = owned(memberId, orderId);
        return view(order);
    }

    public OrderView confirm(long memberId, String orderId, String paymentKey, long amount) {
        if (paymentKey == null || paymentKey.isBlank() || paymentKey.length() > 200) throw ApiException.badRequest("INVALID_PAYMENT_KEY", "결제 키를 확인해 주세요.");
        PaymentOrder reference = owned(memberId, orderId);
        Long operationId = tx().execute(status -> {
            Party party = parties.findByIdForUpdate(reference.getPartyId()).orElseThrow();
            PaymentOrder order = orders.lockById(reference.getId()).orElseThrow();
            if (order.getAmount() != amount) throw ApiException.badRequest("PAYMENT_AMOUNT_MISMATCH", "서버의 주문 금액과 다릅니다.");
            if (order.getPaymentKey() != null && !order.getPaymentKey().equals(paymentKey)) throw ApiException.conflict("PAYMENT_KEY_CHANGED", "이미 등록된 결제 키와 다릅니다.");
            if (order.getStatus() == PaymentStatus.APPROVED) return null;
            if (order.getPurpose() == PaymentPurpose.PREPAY) {
                boolean current = requests.findById(order.getRequestId()).map(r -> r.getStatus() == RequestStatus.MATCHED
                        && r.getParty() != null && r.getParty().getId().equals(party.getId())).orElse(false);
                if (!clock.now().isBefore(order.getDeadlineAt()) || party.getStatus() != PartyStatus.PREPAYING || !current) {
                    throw ApiException.conflict("PAYMENT_EXPIRED", "선결제 기한이 지났거나 일행에서 빠진 요청입니다.");
                }
            } else if (party.getStatus() != PartyStatus.SETTLED) {
                throw ApiException.conflict("NOT_SETTLED", "정산 확정 뒤에만 추가 결제할 수 있습니다.");
            }
            order.setPaymentKey(paymentKey);
            String key = orderId + ":confirm";
            PaymentOperation op = operations.findByIdempotencyKey(key).orElse(null);
            if (op != null) return op.getId();
            order.setStatus(PaymentStatus.PROCESSING);
            op = new PaymentOperation(); op.setPaymentOrderId(order.getId()); op.setType(PaymentOperationType.CONFIRM);
            op.setIdempotencyKey(key); op.setAmount(amount); op.setNextAttemptAt(clock.now());
            return operations.saveAndFlush(op).getId();
        });
        if (operationId != null) execute(operationId);
        return get(memberId, orderId);
    }

    /** 누적 목표를 올릴 때만 차액 작업을 만든다. 같은 논리 취소를 반복해도 한 번이다. */
    @Transactional
    public void refundTo(long orderId, long target, String reason) {
        parties.findByIdForUpdate(orders.findPartyId(orderId).orElseThrow()).orElseThrow();
        PaymentOrder order = orders.lockById(orderId).orElseThrow();
        if (target < 0 || target > order.getAmount()) throw new IllegalArgumentException("환불액 범위 오류");
        if (target <= order.getRefundTarget()) return;
        if (order.getPaymentKey() == null && order.getApprovedAmount() == 0) return;
        PaymentOperation op = new PaymentOperation();
        op.setPaymentOrderId(orderId); op.setType(PaymentOperationType.CANCEL);
        op.setIdempotencyKey(order.getOrderId() + ":refund:" + target);
        op.setAmount(target - order.getRefundTarget()); op.setTargetRefund(target); op.setReason(reason);
        op.setNextAttemptAt(clock.now());
        order.setRefundTarget(target);
        operations.save(op);
    }

    @Transactional
    public void refundAll(long partyId, String reason) {
        parties.findByIdForUpdate(partyId).orElseThrow();
        for (PaymentOrder order : orders.findByPartyIdOrderById(partyId)) {
            refundTo(order.getId(), order.getAmount(), reason);
        }
    }

    @Transactional
    public void refundForDissolution(long partyId) {
        parties.findByIdForUpdate(partyId).orElseThrow();
        for(PaymentOrder order:orders.findByPartyIdOrderById(partyId)) {
            if(!refundHeld(order)) refundTo(order.getId(),order.getAmount(),"일행 해산");
        }
    }
    public boolean refundHeld(PaymentOrder order) {
        return terms.findByPartyIdAndMemberId(order.getPartyId(),order.getMemberId()).map(PartyMemberTerm::isRefundHeld).orElse(false);
    }

    @Transactional
    public void refundMember(long partyId, long memberId, String reason) {
        parties.findByIdForUpdate(partyId).orElseThrow();
        orders.findByPartyIdOrderById(partyId).stream().filter(o -> o.getMemberId() == memberId)
                .forEach(o -> refundTo(o.getId(), o.getAmount(), reason));
    }

    /** 기한에는 재승인보다 조회를 먼저 한다. 통신 실패와 확정 실패를 혼동하지 않는다. */
    public PaymentGateway.Resolution reconcilePrepay(long orderId) {
        PaymentOrder reference=orders.findById(orderId).orElseThrow();
        if(reference.getPurpose()!=PaymentPurpose.PREPAY) throw new IllegalArgumentException("선결제만 조회합니다.");
        if(reference.getStatus()==PaymentStatus.APPROVED) return PaymentGateway.Resolution.APPROVED;
        PaymentOperation op=operations.findByPaymentOrderIdOrderById(orderId).stream().filter(o->o.getType()==PaymentOperationType.CONFIRM).findFirst().orElse(null);
        if(op==null || reference.getPaymentKey()==null) return PaymentGateway.Resolution.FAILED;
        Work work=new Work(op.getId(),reference.getId(),op.getType(),op.getIdempotencyKey(),reference.getPaymentKey(),reference.getOrderId(),op.getAmount(),reference.getAmount(),op.getTargetRefund(),op.getReason(),op.getAttempts());
        PaymentGateway.Payment result=lookup(work);
        PaymentGateway.Resolution resolution=PaymentGateway.Resolution.PENDING;
        if(result!=null && result.orderId().equals(reference.getOrderId()) && result.paymentKey().equals(reference.getPaymentKey()) && result.amount()==reference.getAmount()) {
            if(matches(work,result)) { finish(work,result); resolution=PaymentGateway.Resolution.APPROVED; }
            else if(result.resolution()==PaymentGateway.Resolution.FAILED || result.canceledAmount()>0) resolution=PaymentGateway.Resolution.FAILED;
        }
        PaymentGateway.Resolution resolved=resolution;
        tx().executeWithoutResult(status->{
            parties.findByIdForUpdate(reference.getPartyId()).orElseThrow();
            PaymentOrder current=orders.lockById(orderId).orElseThrow();
            current.setLastCheckedAt(clock.now());
            current.setGatewayStatus(current.getStatus()==PaymentStatus.APPROVED?"APPROVED":resolved.name());
        });
        return orders.findById(orderId).orElseThrow().getStatus()==PaymentStatus.APPROVED?PaymentGateway.Resolution.APPROVED:resolution;
    }

    public void reconcileExcludedPrepayments() {
        for(PaymentOrder order:orders.findByExcludedAtIsNotNullAndApprovedAtIsNull()) {
            if(order.getPaymentKey()!=null && !"FAILED".equals(order.getGatewayStatus())) reconcilePrepay(order.getId());
        }
    }

    /** 외부 취소가 시작되지 않은 경우에만 운행 답변 충돌로 보류할 수 있다. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean pauseUnstartedRefunds(long partyId) {
        List<PaymentOrder> list=orders.findByPartyIdOrderById(partyId);
        if(list.stream().anyMatch(o->o.getRefundedAmount()>0 || operations.findByPaymentOrderIdOrderById(o.getId()).stream()
                .anyMatch(op->op.getType()==PaymentOperationType.CANCEL && op.getAttempts()>0))) return false;
        for(PaymentOrder order:list) {
            for(PaymentOperation op:operations.findByPaymentOrderIdOrderById(order.getId())) if(op.getType()==PaymentOperationType.CANCEL && op.getStatus()==PaymentOperationStatus.PENDING) op.setStatus(PaymentOperationStatus.CANCELED);
            order.setRefundTarget(order.getRefundedAmount());
        }
        return true;
    }

    public void retryDue() {
        for (PaymentOperation op : operations.findByStatusInAndNextAttemptAtLessThanEqualOrderById(
                List.of(PaymentOperationStatus.PENDING, PaymentOperationStatus.PROCESSING), clock.now())) execute(op.getId());
    }

    public void execute(long operationId) {
        Work work = tx().execute(status -> {
            long orderId = operations.findOrderId(operationId).orElseThrow();
            parties.findByIdForUpdate(orders.findPartyId(orderId).orElseThrow()).orElseThrow();
            PaymentOrder order = orders.lockById(orderId).orElseThrow();
            PaymentOperation op = operations.lockById(operationId).orElseThrow();
            if (op.getStatus() == PaymentOperationStatus.SUCCEEDED || op.getStatus() == PaymentOperationStatus.CANCELED || op.getStatus() == PaymentOperationStatus.ADMIN_REQUIRED
                    || (op.getLeaseUntil() != null && op.getLeaseUntil().isAfter(clock.now()))
                    || op.getNextAttemptAt().isAfter(clock.now())) return null;
            if (op.getType() == PaymentOperationType.CANCEL
                    && (order.getApprovedAmount() == 0 || order.getRefundedAmount() < op.getTargetRefund() - op.getAmount())) return null;
            op.setStatus(PaymentOperationStatus.PROCESSING); op.setAttempts(op.getAttempts() + 1);
            op.setLeaseUntil(clock.now().plusSeconds(60)); op.setNextAttemptAt(clock.now().plusSeconds(60));
            return new Work(op.getId(), order.getId(), op.getType(), op.getIdempotencyKey(), order.getPaymentKey(),
                    order.getOrderId(), op.getAmount(), order.getAmount(), op.getTargetRefund(), op.getReason(), op.getAttempts());
        });
        if (work == null) return;
        try {
            PaymentGateway.Payment payment = work.attempts() > 1 ? lookup(work) : null;
            if (!matches(work, payment)) {
                if(work.type()==PaymentOperationType.CONFIRM && orders.findById(work.orderId()).orElseThrow().getExcludedAt()!=null) throw new PaymentGateway.Unavailable("제외 뒤 승인 요청은 재실행하지 않습니다.");
                payment = work.type() == PaymentOperationType.CONFIRM
                        ? gateway.confirm(work.paymentKey(), work.externalOrder(), work.amount(), work.key())
                        : gateway.cancel(work.paymentKey(), work.amount(), work.reason(), work.key());
            }
            if (!matches(work, payment)) throw new PaymentGateway.Unavailable("결제 조회 결과가 주문과 다릅니다.");
            finish(work, payment);
        } catch (RuntimeException failure) {
            PaymentGateway.Payment payment = lookup(work);
            if (matches(work, payment)) finish(work, payment);
            else tx().executeWithoutResult(status -> {
                parties.findByIdForUpdate(orders.findPartyId(work.orderId()).orElseThrow()).orElseThrow();
                PaymentOrder order = orders.lockById(work.orderId()).orElseThrow();
                PaymentOperation op = operations.lockById(work.operationId()).orElseThrow();
                if (op.getStatus() == PaymentOperationStatus.SUCCEEDED) return;
                op.setLeaseUntil(null); op.setLastError(failure.getClass().getSimpleName());
                if (op.getAttempts() >= 4) { op.setStatus(PaymentOperationStatus.ADMIN_REQUIRED); order.setStatus(PaymentStatus.ADMIN_REQUIRED); }
                else op.setStatus(PaymentOperationStatus.PENDING);
            });
        }
    }

    private PaymentGateway.Payment lookup(Work work) {
        try { return work.paymentKey() == null ? gateway.getByOrderId(work.externalOrder()) : gateway.get(work.paymentKey()); }
        catch (RuntimeException unavailable) { return null; }
    }
    private boolean matches(Work work, PaymentGateway.Payment payment) {
        return payment != null && payment.approved() && payment.orderId().equals(work.externalOrder())
                && payment.paymentKey().equals(work.paymentKey()) && payment.amount() == work.orderAmount()
                && payment.canceledAmount() >= 0 && payment.canceledAmount() <= payment.amount()
                && (work.type() == PaymentOperationType.CONFIRM ? payment.canceledAmount() == 0 : payment.canceledAmount() >= work.targetRefund());
    }
    private void finish(Work work, PaymentGateway.Payment result) {
        tx().executeWithoutResult(status -> {
            Party party = parties.findByIdForUpdate(orders.findPartyId(work.orderId()).orElseThrow()).orElseThrow();
            PaymentOrder order = orders.lockById(work.orderId()).orElseThrow();
            PaymentOperation op = operations.lockById(work.operationId()).orElseThrow();
            if (op.getStatus() == PaymentOperationStatus.SUCCEEDED) return;
            op.setStatus(PaymentOperationStatus.SUCCEEDED); op.setCompletedAt(clock.now()); op.setLeaseUntil(null);
            boolean wasExpired = order.getExcludedAt()!=null || order.getStatus() == PaymentStatus.EXPIRED;
            order.setStatus(PaymentStatus.APPROVED); order.setApprovedAmount(result.amount());
            order.setRefundedAmount(Math.max(order.getRefundedAmount(), result.canceledAmount()));
            if (order.getApprovedAt() == null) order.setApprovedAt(clock.now());
            if (work.type() == PaymentOperationType.CONFIRM && (wasExpired
                    || List.of(PartyStatus.NOT_OPERATED, PartyStatus.FARE_MISSING).contains(party.getStatus())
                    || party.getStatus()==PartyStatus.DISSOLVED && !refundHeld(order))) {
                refundTo(order.getId(), order.getAmount(), "기한 종료 뒤 확인된 승인 전액 환불");
            }
            completeIfDone(party);
        });
    }

    @Transactional
    public void completeIfDone(Party party) {
        if (party.getStatus() != PartyStatus.SETTLED) return;
        List<PaymentOrder> list = orders.findByPartyIdOrderById(party.getId());
        if (list.stream().anyMatch(o -> o.getStatus() == PaymentStatus.ADMIN_REQUIRED
                || (o.getPurpose() == PaymentPurpose.ADDITIONAL && o.getStatus() != PaymentStatus.APPROVED)
                || o.getRefundedAmount() < o.getRefundTarget()
                || operations.findByPaymentOrderIdOrderById(o.getId()).stream().anyMatch(op -> op.getStatus() != PaymentOperationStatus.SUCCEEDED))) return;
        if (list.stream().anyMatch(this::refundHeld) || !settlementsRecorded(party)) return;
        party.setStatus(PartyStatus.COMPLETED); party.setSettledAt(clock.now());
    }
    private boolean settlementsRecorded(Party party) {
        return payouts.existsByPartyId(party.getId());
    }
    @Transactional(readOnly = true)
    public boolean overdue(long memberId) {
        return orders.hasUnpaidAdditional(memberId, clock.now());
    }
    private PaymentOrder owned(long memberId, String id) {
        PaymentOrder order = orders.findByOrderId(id).orElseThrow(() -> ApiException.notFound("없는 결제 주문입니다."));
        if (order.getMemberId() != memberId) throw ApiException.forbidden("본인 결제만 처리할 수 있습니다.");
        return order;
    }
    private OrderView view(PaymentOrder o) {
        return new OrderView(o.getOrderId(), o.getAmount(), o.getPurpose(), o.getStatus(), o.getDeadlineAt(),
                o.getApprovedAmount(), o.getRefundedAmount(), o.getRefundTarget(), gateway.simulated());
    }
}
