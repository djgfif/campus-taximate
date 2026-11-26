package com.campustaximate.server.service;

import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.BoardingCase;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.PartyStatus;
import com.campustaximate.server.domain.PaymentOrder;
import com.campustaximate.server.domain.PaymentPurpose;
import com.campustaximate.server.domain.PaymentStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.domain.Settlement;
import com.campustaximate.server.domain.SettlementShare;
import com.campustaximate.server.repository.BoardingCaseRepository;
import com.campustaximate.server.repository.FareObjectionRepository;
import com.campustaximate.server.repository.PaymentOrderRepository;
import com.campustaximate.server.repository.PayoutLedgerRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.repository.SettlementRepository;
import com.campustaximate.server.repository.SettlementShareRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 후불 정산(v3)의 주문 생성·금액 동기화·조사 잠금 여부를 관리한다.
 * SettlementService·NoShowService·PaymentLifecycleService가 함께 쓰는 말단 조정 서비스다.
 */
@Service
@RequiredArgsConstructor
public class PostpaidOrderSyncService {
    private static final long PAYMENT_WINDOW_SECONDS = 24L * 3600L;

    private final RideRequestRepository requests;
    private final SettlementRepository settlements;
    private final SettlementShareRepository shares;
    private final FareObjectionRepository objections;
    private final BoardingCaseRepository boardingCases;
    private final PaymentOrderRepository orders;
    private final PayoutLedgerRepository payouts;
    private final CompensationService compensation;
    private final PaymentService payments;
    private final NotificationService notifications;
    private final AppClock clock;

    /** 관리자 검토·미resolved 이의·탑승 번호/노쇼 조사가 끝나지 않으면 후불 정산 결제를 잠근다. */
    @Transactional(readOnly = true)
    public boolean isPayableLocked(Party party, Settlement settlement) {
        if (!party.postpaidFlow() || settlement == null) return false;
        if (party.getStatus() == PartyStatus.REVIEW_PENDING) return true;
        if (objections.existsBySettlementIdAndResolvedAtIsNull(settlement.getId())) return true;
        if (party.getBoardingDeadlineAt() != null && clock.now().isBefore(party.getBoardingDeadlineAt())) return true;
        return hasPendingBoardingCase(party.getId());
    }

    /** 정산 생성·금액 수정·관리자 조정 뒤 후불 결제 주문을 최신 분담금과 버전에 맞춘다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void syncAfterSave(Party party, Settlement settlement, boolean adminAdjustment) {
        if (!party.postpaidFlow()) return;
        List<SettlementShare> currentShares = shares.findBySettlementIdOrderByRideOrder(settlement.getId());
        if (!hasPendingBoardingCase(party.getId())) {
            compensation.reapplyForPostpaid(party, settlement, currentShares);
        }
        Instant deadline = resolveOrderDeadline(settlement);
        Map<Long, RideRequest> requestByMember = requestsByMember(party);

        for (SettlementShare share : currentShares) {
            if (share.isPayer()) continue;
            RideRequest rider = requestByMember.get(share.getMemberId());
            Long requestId = rider == null ? null : rider.getId();
            PaymentOrder existing = orders
                    .findByPartyIdAndMemberIdAndPurpose(party.getId(), share.getMemberId(), PaymentPurpose.POSTPAID)
                    .orElse(null);

            if (existing == null) {
                PaymentOrder created = payments.createObligation(
                        party.getId(), share.getMemberId(), requestId, settlement.getId(),
                        PaymentPurpose.POSTPAID, share.getAmount(), deadline);
                created.setSettlementRevision(settlement.getRevision());
                continue;
            }
            if (existing.getStatus() == PaymentStatus.PROCESSING
                    || existing.getStatus() == PaymentStatus.ADMIN_REQUIRED) {
                throw ApiException.conflict(
                        existing.getStatus() == PaymentStatus.ADMIN_REQUIRED
                                ? "PAYMENT_ADMIN_REQUIRED" : "PAYMENT_IN_PROGRESS",
                        existing.getStatus() == PaymentStatus.ADMIN_REQUIRED
                                ? "관리자 확인이 필요한 결제가 있어 금액을 조정할 수 없어요."
                                : "진행 중인 결제가 끝난 뒤 금액을 조정할 수 있어요.");
            }
            boolean zeroUncharged = existing.getStatus() == PaymentStatus.APPROVED
                    && existing.getApprovedAmount() == 0
                    && existing.getPaymentKey() == null
                    && existing.getRefundTarget() == 0;
            if (existing.getStatus() == PaymentStatus.APPROVED && !zeroUncharged) {
                if (adminAdjustment) {
                    applyAdminPostPayAdjustment(party, settlement, existing, share, rider, requestId);
                }
                continue;
            }
            syncUnpaidPostpaidOrder(existing, settlement, share.getAmount(), deadline);
        }

        if (payouts.existsByPartyId(party.getId())) {
            long payerShare = currentShares.stream().filter(SettlementShare::isPayer)
                    .mapToLong(SettlementShare::getAmount).findFirst().orElse(0L);
            long expectedPayout = Math.max(0L, settlement.getPaidAmount() - payerShare);
            payouts.findByPartyId(party.getId()).ifPresent(ledger -> ledger.setExpectedAmount(expectedPayout));
            payments.syncPostpaidPayoutLedger(party.getId());
        }
    }

    /** 노쇼 조사가 끝났을 때 정산이 이미 올라와 있으면 보전 차감을 반영해 후불 주문을 다시 맞춘다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshOnCaseResolved(Party party) {
        if (!party.postpaidFlow() || hasPendingBoardingCase(party.getId())) return;
        if (!List.of(PartyStatus.OBJECTION_WINDOW, PartyStatus.REVIEW_PENDING).contains(party.getStatus())) return;
        Settlement settlement = settlements.findByParty(party).orElse(null);
        if (settlement == null) return;

        List<SettlementShare> currentShares = shares.findBySettlementIdOrderByRideOrder(settlement.getId());
        if (currentShares.isEmpty()) return;
        Map<Long, Long> before = new HashMap<>();
        for (SettlementShare share : currentShares) {
            before.put(share.getMemberId(), share.getAmount());
        }
        compensation.reapplyForPostpaid(party, settlement, currentShares);
        boolean changed = currentShares.stream()
                .anyMatch(s -> !Objects.equals(before.get(s.getMemberId()), s.getAmount()));
        if (changed) {
            settlement.setRevision(settlement.getRevision() + 1);
        }
        syncAfterSave(party, settlement, false);
    }

    private void syncUnpaidPostpaidOrder(PaymentOrder order, Settlement settlement, long amount, Instant deadline) {
        order.setSettlementId(settlement.getId());
        order.setDeadlineAt(deadline);
        if (order.getAmount() != amount || !Objects.equals(order.getSettlementRevision(), settlement.getRevision())) {
            order.setOrderId("ctm_" + UUID.randomUUID());
            order.setAmount(amount);
            order.setSettlementRevision(settlement.getRevision());
            order.setPaymentKey(null);
            order.setGatewayStatus(null);
            order.setStatus(amount == 0 ? PaymentStatus.APPROVED : PaymentStatus.REQUIRED);
            order.setApprovedAt(amount == 0 ? clock.now() : null);
        }
    }

    private void applyAdminPostPayAdjustment(Party party, Settlement settlement, PaymentOrder postpaidOrder,
                                             SettlementShare share, RideRequest rider, Long requestId) {
        long approved = postpaidOrder.getApprovedAmount();
        if (approved == 0 && postpaidOrder.getAmount() == 0) {
            // 0원으로 이미 완료 상태였다면 금액이 생겼을 때 추가 정산 주문으로 처리한다.
            approved = 0;
        }
        long netPaid = Math.max(0L, approved - postpaidOrder.getRefundTarget());
        if (share.getAmount() < approved) {
            long targetRefund = approved - share.getAmount();
            if (targetRefund > postpaidOrder.getRefundTarget()) {
                payments.refundTo(postpaidOrder.getId(), targetRefund, "관리자 요금 정정 차액 환불");
            }
            return;
        }
        if (share.getAmount() > netPaid) {
            long delta = share.getAmount() - netPaid;
            Instant deltaDeadline = clock.now().plusSeconds(PAYMENT_WINDOW_SECONDS);
            PaymentOrder existingAdditional = orders
                    .findByPartyIdAndMemberIdAndPurpose(party.getId(), share.getMemberId(), PaymentPurpose.ADDITIONAL)
                    .orElse(null);
            if (existingAdditional == null) {
                PaymentOrder created = payments.createObligation(
                        party.getId(), share.getMemberId(), requestId, settlement.getId(),
                        PaymentPurpose.ADDITIONAL, delta, deltaDeadline);
                created.setSettlementRevision(settlement.getRevision());
            } else if (existingAdditional.getStatus() == PaymentStatus.PROCESSING
                    || existingAdditional.getStatus() == PaymentStatus.ADMIN_REQUIRED) {
                throw ApiException.conflict(
                        existingAdditional.getStatus() == PaymentStatus.ADMIN_REQUIRED
                                ? "PAYMENT_ADMIN_REQUIRED" : "PAYMENT_IN_PROGRESS",
                        existingAdditional.getStatus() == PaymentStatus.ADMIN_REQUIRED
                                ? "관리자 확인이 필요한 추가 결제가 있어 다시 조정할 수 없어요."
                                : "진행 중인 추가 결제가 끝난 뒤 다시 시도해 주세요.");
            } else if (existingAdditional.getStatus() == PaymentStatus.REQUIRED
                    || existingAdditional.getStatus() == PaymentStatus.OVERDUE) {
                existingAdditional.setOrderId("ctm_" + UUID.randomUUID());
                existingAdditional.setAmount(delta);
                existingAdditional.setSettlementRevision(settlement.getRevision());
                existingAdditional.setSettlementId(settlement.getId());
                existingAdditional.setPaymentKey(null);
                existingAdditional.setDeadlineAt(deltaDeadline);
                existingAdditional.setStatus(PaymentStatus.REQUIRED);
            }
            if (rider != null) {
                notifications.toMember(rider.getMember(), "[캠퍼스 택시메이트] 추가 분담금 결제 요청",
                        "관리자 정산 정정으로 추가 분담금 " + delta + "원 결제가 필요해요. 24시간 안에 결제해 주세요.");
            }
        }
    }

    private Instant resolveOrderDeadline(Settlement settlement) {
        Instant base = settlement.getFinalizedAt() != null
                ? settlement.getFinalizedAt()
                : settlement.getObjectionDeadlineAt();
        if (base == null || base.isBefore(clock.now())) {
            base = clock.now();
        }
        return base.plusSeconds(PAYMENT_WINDOW_SECONDS);
    }

    private boolean hasPendingBoardingCase(long partyId) {
        return boardingCases.findByPartyIdOrderByRequestId(partyId).stream().anyMatch(BoardingCase::pending);
    }

    private Map<Long, RideRequest> requestsByMember(Party party) {
        Map<Long, RideRequest> map = new HashMap<>();
        for (RideRequest rider : requests.findByPartyOrderByRideOrder(party)) {
            map.put(rider.getMember().getId(), rider);
        }
        return map;
    }
}
