package com.campustaximate.server.service;

import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import com.campustaximate.matching.Direction;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 1분 회차와 시연 시계가 같은 기한 처리를 사용한다. */
@Service
@RequiredArgsConstructor
public class PaymentLifecycleService {
    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final PaymentOrderRepository orders;
    private final SettlementRepository settlements;
    private final SettlementShareRepository shares;
    private final FareObjectionRepository objections;
    private final PayoutLedgerRepository payouts;
    private final PaymentService payments;
    private final PartyShrinker shrinker;
    private final RideConfirmationService rideConfirmations;
    private final NotificationService notifications;
    private final AppClock clock;
    private final PlatformTransactionManager transactionManager;
    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }

    /** 출발 전에 먼저 처리해 뒤늦은 스케줄러 실행이 운행 미성사를 운행 시작으로 바꾸지 않는다. */
    public void beforeDepartures() {
        payments.reconcileExcludedPrepayments();
        for (Party candidate : parties.findByStatus(PartyStatus.PREPAYING)) {
            if (!candidate.paymentFlow() || clock.now().isBefore(candidate.getPrepayDeadlineAt())) continue;
            // PG 조회는 일행 트랜잭션 밖에서 하고 적용 직전에 잠금으로 최신 상태를 확인한다.
            for(PaymentOrder order:orders.findByPartyIdOrderById(candidate.getId())) {
                if(order.getPurpose()==PaymentPurpose.PREPAY && order.getStatus()!=PaymentStatus.APPROVED && order.getExcludedAt()==null) payments.reconcilePrepay(order.getId());
            }
            Set<Long> expired=tx().execute(status->{
                Party party=parties.findByIdForUpdate(candidate.getId()).orElseThrow();
                if(party.getStatus()!=PartyStatus.PREPAYING) return Set.of();
                Set<Long> ids=new HashSet<>();
                for(PaymentOrder reference:orders.findByPartyIdOrderById(party.getId())) {
                    PaymentOrder order=orders.lockById(reference.getId()).orElseThrow();
                    if(order.getPurpose()!=PaymentPurpose.PREPAY) continue;
                    boolean current=requests.findById(order.getRequestId()).map(r->r.getStatus()==RequestStatus.MATCHED && r.getParty()!=null && r.getParty().getId().equals(party.getId())).orElse(false);
                    if(!current) continue;
                    if(order.getExcludedAt()!=null) {ids.add(order.getRequestId());continue;}
                    if(order.getMemberId().equals(party.getPayer().getId()) || order.getStatus()==PaymentStatus.APPROVED) continue;
                    boolean pending=order.getPaymentKey()!=null && !"FAILED".equals(order.getGatewayStatus());
                    if(pending && clock.now().isBefore(party.paymentCheckUntil())) continue;
                    order.setStatus(PaymentStatus.EXPIRED); order.setExcludedAt(clock.now()); ids.add(order.getRequestId());
                }
                return ids;
            });
            if(expired!=null && !expired.isEmpty()) shrinker.remove(candidate.getId(),expired,clock.now(),false,"PREPAY_UNPAID",null,Set.of());
            tx().executeWithoutResult(status->{
                Party party=parties.findByIdForUpdate(candidate.getId()).orElseThrow();
                if(party.getStatus()!=PartyStatus.PREPAYING) return;
                boolean pending=orders.findByPartyIdOrderById(party.getId()).stream().anyMatch(o->o.getPurpose()==PaymentPurpose.PREPAY
                        && !o.getMemberId().equals(party.getPayer().getId()) && o.getExcludedAt()==null && o.getStatus()!=PaymentStatus.APPROVED
                        && requests.findById(o.getRequestId()).map(r->r.getParty()!=null && r.getParty().getId().equals(party.getId())).orElse(false));
                if(!pending) party.setStatus(PartyStatus.READY);
            });
        }
        for(Party candidate:parties.findByStatus(PartyStatus.READY)) {
            if(!candidate.paymentFlow() || candidate.getStartedAt()!=null || clock.now().isBefore(candidate.getDepartureAt().plusSeconds(300))) continue;
            tx().executeWithoutResult(status->{
                Party party=parties.findByIdForUpdate(candidate.getId()).orElseThrow();
                if(party.getStatus()==PartyStatus.READY && party.getStartedAt()==null) {
                    if(party.getBoardedAt()!=null) rideConfirmations.start(party,party.getBoardedAt());
                    else rideConfirmations.ask(party);
                }
            });
        }


    }

    public void afterDepartures() {
        for(Party candidate:parties.findByStatus(PartyStatus.RIDE_UNCONFIRMED)) tx().executeWithoutResult(status->{
            Party party=parties.findByIdForUpdate(candidate.getId()).orElseThrow();
            if(party.getStatus()==PartyStatus.RIDE_UNCONFIRMED && !clock.now().isBefore(party.getDepartureAt().plusSeconds(24*3600))) rideConfirmations.notOperated(party);
        });
        for (Party candidate : parties.findByStatus(PartyStatus.AWAITING_FARE)) {
            tx().executeWithoutResult(status -> {
                Party party = parties.findByIdForUpdate(candidate.getId()).orElseThrow();
                if (party.getStatus() != PartyStatus.AWAITING_FARE || settlements.findByParty(party).isPresent()) return;
                Instant start = party.getStartedAt() == null ? party.getDepartureAt() : party.getStartedAt();
                if (!clock.now().isBefore(start.plusSeconds(24 * 3600))) {
                    party.setStatus(PartyStatus.FARE_MISSING); payments.refundAll(party.getId(), "정산 금액 미입력");
                } else if (party.getFareRemindedAt() == null && !clock.now().isBefore(start.plusSeconds(12 * 3600))) {
                    notifications.toMember(party.getPayer(), "[캠퍼스 택시메이트] 정산 금액 입력 안내", "운행 금액과 영수증을 입력해 주세요. 출발 후 24시간까지 입력할 수 있어요.");
                    party.setFareRemindedAt(clock.now());
                }
            });
        }
        for (Party candidate : parties.findByStatus(PartyStatus.OBJECTION_WINDOW)) {
            tx().executeWithoutResult(status -> finalizeSettlement(candidate.getId()));
        }
        for(PaymentOrder candidate:orders.findByStatusAndDeadlineAtLessThanEqual(PaymentStatus.REQUIRED,clock.now())) {
            if(candidate.getPurpose()!=PaymentPurpose.ADDITIONAL) continue;
            tx().executeWithoutResult(status->{
                parties.findByIdForUpdate(candidate.getPartyId()).orElseThrow();
                PaymentOrder order=orders.lockById(candidate.getId()).orElseThrow();
                if(order.getStatus()==PaymentStatus.REQUIRED && !clock.now().isBefore(order.getDeadlineAt())) order.setStatus(PaymentStatus.OVERDUE);
            });
        }
        payments.retryDue();
        for (Party candidate : parties.findByStatus(PartyStatus.SETTLED)) tx().executeWithoutResult(status ->
                payments.completeIfDone(parties.findByIdForUpdate(candidate.getId()).orElseThrow()));
    }

    private void finalizeSettlement(long partyId) {
        Party party = parties.findByIdForUpdate(partyId).orElseThrow();
        if (party.getStatus() != PartyStatus.OBJECTION_WINDOW) return;
        Settlement settlement = settlements.findByParty(party).orElseThrow();
        if (clock.now().isBefore(settlement.getObjectionDeadlineAt())) return;
        if (objections.existsBySettlementId(settlement.getId())) { party.setStatus(PartyStatus.REVIEW_PENDING); return; }
        party.setStatus(PartyStatus.SETTLED); settlement.setFinalizedAt(clock.now());
        List<SettlementShare> allocations = shares.findBySettlementIdOrderByRideOrder(settlement.getId());
        for (PaymentOrder order : orders.findByPartyIdOrderById(partyId)) {
            if (order.getPurpose() != PaymentPurpose.PREPAY || order.getApprovedAmount() == 0 || payments.refundHeld(order)) continue;
            SettlementShare share = allocations.stream().filter(s -> s.getMemberId().equals(order.getMemberId())).findFirst().orElse(null);
            // 새 결제자로 바뀐 사람의 선결제와 기존 정책상 보전하지 않는 이탈자의 금액도 여기서 돌려준다.
            long refund = share == null || share.isPayer() ? order.getApprovedAmount() : Math.max(0, order.getApprovedAmount() - share.getAmount());
            payments.refundTo(order.getId(), refund, "정산 확정 차액 환불");
        }
        for (SettlementShare share : allocations) {
            if (share.isPayer()) continue;
            long prepay = orders.findByPartyIdAndMemberIdAndPurpose(partyId, share.getMemberId(), PaymentPurpose.PREPAY)
                    .map(PaymentOrder::getApprovedAmount).orElse(0L);
            if (share.getAmount() > prepay) {
                payments.createObligation(partyId, share.getMemberId(), null, settlement.getId(),
                        PaymentPurpose.ADDITIONAL, share.getAmount() - prepay, clock.now().plusSeconds(24 * 3600));
                requests.findByPartyOrderByRideOrder(party).stream().filter(r -> r.getMember().getId().equals(share.getMemberId())).findFirst()
                        .ifPresent(r -> notifications.toMember(r.getMember(), "[캠퍼스 택시메이트] 추가 결제 요청", "정산 차액 " + (share.getAmount() - prepay) + "원을 24시간 안에 결제해 주세요."));
            }
        }
        SettlementShare payer = allocations.stream().filter(SettlementShare::isPayer).findFirst().orElseThrow();
        if (!payouts.existsBySettlementId(settlement.getId())) {
            PayoutLedger ledger = new PayoutLedger(); ledger.setSettlementId(settlement.getId()); ledger.setPartyId(partyId);
            ledger.setMemberId(payer.getMemberId()); ledger.setAmount(settlement.getPaidAmount() - payer.getAmount());
            ledger.setRecordedAt(clock.now()); payouts.save(ledger);
        }
    }
}
