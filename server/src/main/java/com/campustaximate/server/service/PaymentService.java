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
@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    private static final Set<String> INDEPENDENT_EXIT_REFUNDS =
            Set.of("FREE_CANCEL", "CHANGE_EXIT_FREE", "PREPAY_UNPAID", "ROUTE_VERIFICATION_EXPIRED");
    private final PaymentOrderRepository orders;
    private final PaymentOperationRepository operations;
    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final PayoutLedgerRepository payouts;
    private final CompensationEntryRepository dissolutionEntries;
    private final PaymentGateway gateway;
    private final JoinCompletionService joinCompletion;
    private final AppClock clock;
    private final PartyChangeService partyChanges;
    private final com.campustaximate.matching.MatchingSettings matchingSettings;
    private final PartyMemberTermRepository terms;
    private final NotificationService notifications;
    private final PlatformTransactionManager transactionManager;
    private final PartyChatService chatLog;

    public record OrderView(String orderId, long amount, PaymentPurpose purpose, PaymentStatus status,
                            Instant deadlineAt, long approvedAmount, long refundedAmount, long refundRequestedAmount, boolean simulated, boolean refundHeld, String holdReason) { }
    private record Work(long operationId, long orderId, PaymentOperationType type, String key,
                        String paymentKey, String externalOrder, long amount, long orderAmount,
                        long targetRefund, String reason, int attempts) { }
    public boolean simulated() { return gateway.simulated(); }
    private TransactionTemplate tx() { return new com.campustaximate.server.common.RetryingTransactionTemplate(transactionManager); }

    /**
     * 확정 분담금(S43): 선결제 = 확정된 내 몫 그대로이고 그것이 최종 금액이다. 실제 미터와의 차이는 정산 적립금이 맡아서
     * 일반 운행에서 나중에 더 내는 일이 없다(실험 C11). 예전에는 미터 오차를 대비해 115%를 받고 차액을 정산했다.
     */
    public static long prepayAmount(long share) { return share; }
    /** 선결제 기한 [S39]: 예약은 출발 20분 전, 즉시는 확정 + 5분(S41: 결제창과 카드사 인증에 3분은 빠듯했다). 확정 + 5분보다 이르면 확정 + 5분(안전장치). */
    public static Instant prepayDeadline(Instant confirmed, Instant departure, boolean instant) {
        if (instant) return confirmed.plusSeconds(com.campustaximate.matching.MatchingSettings.everyMinute().instantPrepMinutes()*60L);
        Instant minimum = confirmed.plusSeconds(300);
        Instant normal = departure.minusSeconds(1200);
        return normal.isBefore(minimum) ? minimum : normal;
    }

    @Transactional
    public void preparePrepayments(Party party, List<RideRequest> riders, Instant slot) {
        party.setPaymentFlowVersion(2);
        if(party.getOriginalPayerId()==null) party.setOriginalPayerId(party.getPayer().getId());
        party.setConfirmedAt(slot);
        party.setInstant(riders.stream().anyMatch(r -> r.getMode() == RequestMode.INSTANT));
        party.setPrepayDeadlineAt(party.isInstant()?slot.plusSeconds(matchingSettings.instantPrepMinutes()*60L):prepayDeadline(slot,party.getDepartureAt(),false));
        party.setStatus(PartyStatus.PREPAYING);
        for (RideRequest rider : riders) {
            if (!rider.getMember().getId().equals(party.getPayer().getId())) {
                createObligation(party.getId(), rider.getMember().getId(), rider.getId(), null,
                        PaymentPurpose.PREPAY, prepayAmount(rider.getExpectedShare()), party.getPrepayDeadlineAt());
            }
        }
        partyChanges.capture(party, riders, false);
        if(party.isInstant()) notifications.toMember(party.getPayer(),"[캠퍼스 택시메이트] 승차 지점으로 출발", "즉시 일행이 확정됐어요. 지금 승차 지점으로 출발해 주세요.");
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

    /** 종료된 요청도 본인의 주문만 조회한다. 일행 상세나 다른 일행원의 정보는 반환하지 않는다. */
    @Transactional(readOnly = true)
    public List<OrderView> history(long memberId, long requestId) {
        RideRequest request = requests.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("없는 요청입니다."));
        if (request.getMember().getId() != memberId) throw ApiException.forbidden("본인의 결제 기록만 볼 수 있어요.");
        Set<Long> partyIds = new HashSet<>();
        if (request.getParty() != null) partyIds.add(request.getParty().getId());
        terms.findByRequestIdOrderById(requestId).stream().filter(t -> t.getMemberId() == memberId)
                .forEach(t -> partyIds.add(t.getPartyId()));
        if (partyIds.isEmpty()) return List.of();
        return orders.findByMemberIdAndPartyIdInOrderById(memberId, partyIds).stream().map(this::view).toList();
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
            if (order.isRideReviewHeld()) throw ApiException.conflict("RIDE_REVIEW_PAYMENT_HELD",
                    "운행 검토가 끝날 때까지 이 결제는 보류돼요.");
            if (order.getPurpose() == PaymentPurpose.PREPAY) {
                boolean current = requests.findById(order.getRequestId()).map(r -> r.getStatus() == RequestStatus.MATCHED
                        && r.getParty() != null && r.getParty().getId().equals(party.getId())).orElse(false);
                if (!clock.now().isBefore(order.getDeadlineAt()) || !joinCompletion.pending(order,party) && (party.getStatus() != PartyStatus.PREPAYING || !current)) {
                    throw ApiException.conflict("PAYMENT_EXPIRED", "선결제 기한이 지났거나 일행에서 빠진 요청입니다.");
                }
            } else if (party.getStatus() != PartyStatus.SETTLED && !(party.getStatus()==PartyStatus.DISSOLVED
                    && dissolutionEntries.findByPartyIdOrderByRequestId(party.getId()).stream().anyMatch(e->e.getMemberId().equals(memberId) && e.getCharge()>0))) {
                throw ApiException.conflict("NOT_SETTLED", "정산 확정 뒤에만 추가 결제할 수 있습니다.");
            }
            // 결제 키마다 작업을 따로 둔다. 거절된 결제 뒤에 새 결제 키로 다시 승인할 수 있어야 한다.
            String key = orderId + ":confirm:" + shortHash(paymentKey);
            PaymentOperation op = operations.findByIdempotencyKey(key).orElse(null);
            if (op != null && op.getStatus() == PaymentOperationStatus.CANCELED)
                throw ApiException.conflict("PAYMENT_REJECTED", "결제사가 거절한 결제예요. 다시 결제해 주세요.");
            order.setPaymentKey(paymentKey);
            if (op != null) return op.getId();
            order.setStatus(PaymentStatus.PROCESSING);
            op = new PaymentOperation(); op.setPaymentOrderId(order.getId()); op.setType(PaymentOperationType.CONFIRM);
            op.setIdempotencyKey(key); op.setAmount(amount); op.setNextAttemptAt(clock.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
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
        if (order.isRideReviewHeld() || target <= order.getRefundTarget()) return;
        if (order.getPaymentKey() == null && order.getApprovedAmount() == 0) return;
        String key = order.getOrderId() + ":refund:" + target;
        PaymentOperation op = operations.findByIdempotencyKey(key).orElse(null);
        if (op != null && !discardedRefund(op)) {
            order.setRefundTarget(target);
            return;
        }
        if (op == null) {
            op = new PaymentOperation();
            op.setPaymentOrderId(orderId);
            op.setType(PaymentOperationType.CANCEL);
            op.setIdempotencyKey(key);
        }
        op.setStatus(PaymentOperationStatus.PENDING);
        op.setAmount(target - order.getRefundTarget()); op.setTargetRefund(target); op.setReason(reason);
        op.setNextAttemptAt(clock.now());
        op.setCompletedAt(null);
        order.setRefundTarget(target);
        operations.save(op);
    }

    @Transactional
    public void refundAll(long partyId, String reason) {
        parties.findByIdForUpdate(partyId).orElseThrow();
        terms.findByPartyIdOrderById(partyId).forEach(t->t.setRefundHeld(false));
        for (PaymentOrder order : orders.findByPartyIdOrderById(partyId)) {
            refundTo(order.getId(), order.getAmount(), reason);
        }
    }

    /** 일행 잠금 아래, 요청을 지우기 전에 남은 한 사람과 이번 이탈자를 확정한다. */
    @Transactional
    public void refundForDissolution(long partyId, Set<Long> leavingRequestIds) {
        Party party=parties.findByIdForUpdate(partyId).orElseThrow();
        if(party.paymentFlow() && party.getStartedAt()==null && party.getStatus()==PartyStatus.DISSOLVED
                && dissolutionEntries.findByPartyIdOrderByRequestId(partyId).isEmpty() && !payouts.existsByPartyId(partyId)) {
            var remaining=requests.findByPartyOrderByRideOrder(party).stream().filter(r->!leavingRequestIds.contains(r.getId())).toList();
            var original=terms.findByPartyIdOrderById(partyId);
            // 과거 미납·무료 이탈자는 제외하고, 이번 직전 취소자만 부담한다. 결제자도 예외가 아니다.
            var liable=original.stream().filter(t->leavingRequestIds.contains(t.getRequestId()) && "LATE_CANCEL".equals(t.getExitReason()))
                    .sorted(Comparator.comparing(PartyMemberTerm::getRequestId)).toList();
            if(remaining.size()==1 && !liable.isEmpty()) {
                RideRequest survivor=remaining.getFirst();
                var survivorTerm=original.stream().filter(t->t.getRequestId().equals(survivor.getId())).findFirst().orElseThrow();
                long cap=liable.stream().mapToLong(PartyMemberTerm::getConfirmedShare).sum();
                long increase=Math.max(0,survivor.getSoloFare()-survivorTerm.getConfirmedShare());
                long total=com.campustaximate.settlement.SettlementCalculator.compensation(List.of(increase),cap).getFirst();
                var charges=com.campustaximate.settlement.SettlementCalculator.compensationBurdens(total,
                        liable.stream().map(PartyMemberTerm::getConfirmedShare).toList(),liable.stream().map(PartyMemberTerm::getRequestId).toList());
                for(int i=0;i<liable.size();i++) saveDissolutionEntry(partyId,liable.get(i),0,0,charges.get(i),charges.get(i));
                saveDissolutionEntry(partyId,survivorTerm,survivor.getSoloFare(),total,0,survivor.getSoloFare()-total);
                PayoutLedger ledger=new PayoutLedger();ledger.setPartyId(partyId);ledger.setMemberId(survivor.getMember().getId());
                ledger.setAmount(total);ledger.setRecordedAt(clock.now());payouts.save(ledger);
                party.setSettledAt(clock.now());
            }
        }
        refundForDissolution(partyId);
    }

    private void saveDissolutionEntry(long partyId,PartyMemberTerm term,long raw,long credit,long charge,long net) {
        CompensationEntry entry=new CompensationEntry();entry.setPartyId(partyId);entry.setRequestId(term.getRequestId());entry.setMemberId(term.getMemberId());
        entry.setRawShare(raw);entry.setCredit(credit);entry.setCharge(charge);entry.setNetShare(net);dissolutionEntries.save(entry);
    }

    /** 재처리는 확정된 부담액을 사용하며 전액 환불로 덮어쓰지 않는다. */
    @Transactional
    public void refundForDissolution(long partyId) {
        parties.findByIdForUpdate(partyId).orElseThrow();
        var journal=dissolutionEntries.findByPartyIdOrderByRequestId(partyId);
        terms.findByPartyIdOrderById(partyId).forEach(t->t.setRefundHeld(false));
        for(PaymentOrder order:orders.findByPartyIdOrderById(partyId)) {
            if(order.getPurpose()!=PaymentPurpose.PREPAY) continue;
            long charge=journal.stream().filter(e->e.getMemberId().equals(order.getMemberId())).mapToLong(CompensationEntry::getCharge).sum();
            refundTo(order.getId(),Math.max(0,order.getAmount()-charge),"일행 해산 보전 확정 환불");
        }
        // 원래 결제자처럼 선결제가 없으면 같은 보전 부담을 기존 추가 결제 흐름으로 받는다.
        for(var entry:journal) if(entry.getCharge()>0) {
            var prepay=orders.findByPartyIdAndMemberIdAndPurpose(partyId,entry.getMemberId(),PaymentPurpose.PREPAY).orElse(null);
            long coverage=prepay==null?0:prepay.getApprovedAmount()>0?prepay.getApprovedAmount():
                    prepay.getPaymentKey()!=null && !"FAILED".equals(prepay.getGatewayStatus())?prepay.getAmount():0;
            if(entry.getCharge()>coverage) createObligation(partyId,entry.getMemberId(),entry.getRequestId(),null,
                    PaymentPurpose.ADDITIONAL,entry.getCharge()-coverage,clock.now().plusSeconds(86400));
        }
    }
    public boolean refundHeld(PaymentOrder order) {
        return order.isRideReviewHeld()
                || terms.findByPartyIdAndMemberId(order.getPartyId(),order.getMemberId()).map(PartyMemberTerm::isRefundHeld).orElse(false);
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
        if(reference.isRideReviewHeld()) return PaymentGateway.Resolution.PENDING;
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

    /** 일행 잠금 아래 운행 판단에 따라 달라지는 주문만 보류한다. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean pauseUnstartedRefunds(long partyId) {
        boolean refundStarted = false;
        for (PaymentOrder order : rideOrders(partyId)) {
            var work = operations.findByPaymentOrderIdOrderById(order.getId());
            refundStarted |= order.getRefundedAmount() > 0 || work.stream().anyMatch(PaymentService::attemptedRefund);
            order.setRideReviewHeld(true);
            if (work.stream().noneMatch(PaymentService::uncertainRefund)) {
                discardUnsentRefunds(order, work);
            }
        }
        return !refundStarted;
    }

    /**
     * A completed prepayment refund is consistent with non-operation. An attempted refund
     * with an unknown result is not reconciled by guessing what the gateway did.
     */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean resolveRideDecision(long partyId, boolean operated) {
        var affected = rideOrders(partyId);
        boolean blocked = payouts.existsByPartyId(partyId);
        for (PaymentOrder order : affected) {
            var work = operations.findByPaymentOrderIdOrderById(order.getId());
            boolean extraCharge = order.getPurpose() == PaymentPurpose.ADDITIONAL
                    && (order.getApprovedAmount() > order.getRefundedAmount()
                    || work.stream().anyMatch(op -> op.getType() == PaymentOperationType.CONFIRM
                    && op.getAttempts() > 0 && op.getStatus() != PaymentOperationStatus.CANCELED
                    && op.getStatus() != PaymentOperationStatus.SUCCEEDED));
            boolean refundConflict = operated
                    ? order.getRefundedAmount() > 0 || work.stream().anyMatch(PaymentService::attemptedRefund)
                    : work.stream().anyMatch(PaymentService::uncertainRefund);
            order.setRideReviewConflict(extraCharge || refundConflict);
            blocked |= order.isRideReviewConflict();
        }
        for (PaymentOrder order : affected) {
            order.setRideReviewHeld(blocked);
            if (blocked) continue;
            var work = operations.findByPaymentOrderIdOrderById(order.getId());
            if (operated) {
                discardUnsentRefunds(order, work);
            } else if (order.getPurpose() == PaymentPurpose.ADDITIONAL && order.getApprovedAmount() == 0) {
                // A quote which has not been sent is no longer collectible after non-operation.
                work.stream().filter(op -> op.getType() == PaymentOperationType.CONFIRM && isUnsent(op))
                        .forEach(op -> op.setStatus(PaymentOperationStatus.CANCELED));
                order.setStatus(PaymentStatus.EXPIRED);
            }
        }
        return !blocked;
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void refundNonOperation(long partyId) {
        var membersInScope = new HashSet<Long>();
        for (PaymentOrder order : rideOrders(partyId)) {
            membersInScope.add(order.getMemberId());
            if (order.getPurpose() == PaymentPurpose.PREPAY) refundTo(order.getId(), order.getAmount(), "운행 미성사 확인");
        }
        terms.findByPartyIdOrderById(partyId).stream().filter(t -> membersInScope.contains(t.getMemberId()))
                .forEach(t -> t.setRefundHeld(false));
    }

    private List<PaymentOrder> rideOrders(long partyId) {
        var memberTerms = new HashMap<Long, PartyMemberTerm>();
        for (PartyMemberTerm term : terms.findByPartyIdOrderById(partyId)) {
            memberTerms.put(term.getRequestId(), term);
        }
        return orders.findByPartyIdOrderById(partyId).stream().filter(order -> {
            if (order.getPurpose() != PaymentPurpose.PREPAY) return true;
            PartyMemberTerm term = memberTerms.get(order.getRequestId());
            if (term == null) return order.getExcludedAt() == null;
            return term.getExitedAt() == null || term.getExitReason() == null
                    || !INDEPENDENT_EXIT_REFUNDS.contains(term.getExitReason());
        }).toList();
    }

    private static boolean isUnsent(PaymentOperation op) {
        return op.getStatus() == PaymentOperationStatus.PENDING && op.getAttempts() == 0;
    }

    private static boolean attemptedRefund(PaymentOperation op) {
        return op.getType() == PaymentOperationType.CANCEL && op.getStatus() != PaymentOperationStatus.CANCELED
                && (op.getAttempts() > 0 || op.getStatus() != PaymentOperationStatus.PENDING);
    }

    private static boolean uncertainRefund(PaymentOperation op) {
        return attemptedRefund(op) && op.getStatus() != PaymentOperationStatus.SUCCEEDED;
    }

    private static boolean discardedRefund(PaymentOperation op) {
        return op.getType() == PaymentOperationType.CANCEL && op.getStatus() == PaymentOperationStatus.CANCELED
                && op.getAttempts() == 0;
    }

    private void discardUnsentRefunds(PaymentOrder order, List<PaymentOperation> work) {
        work.stream().filter(op -> op.getType() == PaymentOperationType.CANCEL && isUnsent(op)).forEach(op -> {
            op.setStatus(PaymentOperationStatus.CANCELED);
            op.setCompletedAt(clock.now());
        });
        order.setRefundTarget(order.getRefundedAmount());
    }

    public void retryDue() {
        // 승인 조회가 그 회차에 새 환불을 만들 수 있다. 처음 읽은 목록만 실행하면 다음 회차까지 미완료가 남는다.
        Set<Long> attempted=new HashSet<>();
        Set<Long> touched=new HashSet<>();
        while(attempted.size()<1000) {
            List<PaymentOperation> due=operations.findDue(
                    List.of(PaymentOperationStatus.PENDING,PaymentOperationStatus.PROCESSING),clock.now()).stream()
                    .filter(op->!attempted.contains(op.getId())).toList();
            if(due.isEmpty()) break;
            for(PaymentOperation op:due) {
                attempted.add(op.getId());
                orders.findPartyId(op.getPaymentOrderId()).ifPresent(touched::add);
                try {execute(op.getId());}
                catch(RuntimeException failure) {log.error("결제 작업 {} 적용 실패. 다른 일행의 작업은 계속합니다.",op.getId(),failure);}
            }
        }
        for(Long id:touched) tx().executeWithoutResult(status->{
            Party party=parties.findByIdForUpdate(id).orElse(null);
            if(party!=null) completeIfDone(party);
        });
    }

    public void execute(long operationId) {
        Work work = tx().execute(status -> {
            long orderId = operations.findOrderId(operationId).orElseThrow();
            parties.findByIdForUpdate(orders.findPartyId(orderId).orElseThrow()).orElseThrow();
            PaymentOrder order = orders.lockById(orderId).orElseThrow();
            PaymentOperation op = operations.lockById(operationId).orElseThrow();
            if (order.isRideReviewHeld()) return null;
            if (op.getStatus() == PaymentOperationStatus.SUCCEEDED || op.getStatus() == PaymentOperationStatus.CANCELED || op.getStatus() == PaymentOperationStatus.ADMIN_REQUIRED
                    || (op.getLeaseUntil() != null && op.getLeaseUntil().isAfter(clock.now()))
                    || op.getNextAttemptAt().isAfter(clock.now())) return null;
            if (op.getType() == PaymentOperationType.CANCEL
                    && (order.getApprovedAmount() == 0 || order.getRefundedAmount() < op.getTargetRefund() - op.getAmount())) return null;
            op.setStatus(PaymentOperationStatus.PROCESSING); op.setAttempts(op.getAttempts() + 1);
            op.setLeaseUntil(clock.now().plusSeconds(60)); op.setNextAttemptAt(clock.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
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
                if (failure instanceof PaymentGateway.Rejected rejected && work.type() == PaymentOperationType.CONFIRM) {
                    // 확정 거절: 재시도하지 않고 주문을 결제 전 상태로 돌려 새 결제를 받을 수 있게 한다.
                    op.setStatus(PaymentOperationStatus.CANCELED); op.setCompletedAt(clock.now());
                    op.setLastError("Rejected:" + rejected.code());
                    if (order.getStatus() == PaymentStatus.PROCESSING) { order.setStatus(PaymentStatus.REQUIRED); order.setPaymentKey(null); }
                    return;
                }
                if (op.getAttempts() >= 4) { op.setStatus(PaymentOperationStatus.ADMIN_REQUIRED); order.setStatus(PaymentStatus.ADMIN_REQUIRED); }
                else op.setStatus(PaymentOperationStatus.PENDING);
            });
        }
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
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
            // Record an already leased request, but don't enqueue its follow-up during review.
            if (order.isRideReviewHeld()) return;
            if (work.type() == PaymentOperationType.CONFIRM && !wasExpired && order.getPurpose() == PaymentPurpose.PREPAY
                    && List.of(PartyStatus.PREPAYING, PartyStatus.READY).contains(party.getStatus()))
                chatLog.system(party, order.getMemberId(), "%s님이 선결제를 마쳤어요.");
            if(work.type()==PaymentOperationType.CONFIRM && joinCompletion.approved(order,party)) {
                order.setExcludedAt(clock.now());refundTo(order.getId(),order.getAmount(),"합류 만료 또는 일행 변경 뒤 승인 전액 환불");
            } else if(work.type()==PaymentOperationType.CONFIRM && party.getStatus()==PartyStatus.DISSOLVED && order.getPurpose()==PaymentPurpose.PREPAY) {
                refundForDissolution(party.getId());
            } else if (work.type() == PaymentOperationType.CONFIRM && (wasExpired
                    || List.of(PartyStatus.NOT_OPERATED, PartyStatus.FARE_MISSING).contains(party.getStatus()))) {
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
                || operations.findByPaymentOrderIdOrderById(o.getId()).stream().anyMatch(op -> op.getStatus() != PaymentOperationStatus.SUCCEEDED
                        && !discardedRefund(op)
                        && !(op.getType()==PaymentOperationType.CONFIRM && op.getStatus()==PaymentOperationStatus.CANCELED)))) return;
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
        boolean held = refundHeld(o);
        String holdReason = o.isRideReviewHeld() ? "보류(운행·정산 검토 중)" :
                held ? terms.findByPartyIdAndMemberId(o.getPartyId(),o.getMemberId())
                        .map(t -> "BOARDING_UNCONFIRMED".equals(t.getExitReason()) || "PAYER_NO_SHOW".equals(t.getExitReason())
                                ? "보류(노쇼 조사 중)" : "보전 정산 대기").orElse(null) : null;
        return new OrderView(o.getOrderId(), o.getAmount(), o.getPurpose(), o.getStatus(), o.getDeadlineAt(),
                o.getApprovedAmount(), o.getRefundedAmount(), o.getRefundTarget(), gateway.simulated(), held, holdReason);
    }
}
