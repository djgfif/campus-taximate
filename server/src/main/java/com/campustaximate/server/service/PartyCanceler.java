package com.campustaximate.server.service;

import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.EndReason;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.PartyStatus;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.repository.RideRequestRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 누가 빠지면 일행을 취소한다. 재검사와 재배정은 하지 않는다. */
@Component
@RequiredArgsConstructor
public class PartyCanceler {

    private final RideRequestRepository requests;
    private final NotificationService notifications;
    private final PaymentService payments;

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(Party party, Set<Long> leavingRequestIds, Instant now) {
        List<RideRequest> riders = requests.findByPartyOrderByRideOrder(party);
        party.setStatus(party.paymentFlow() ? PartyStatus.DISSOLVED : PartyStatus.CANCELED);
        if (party.paymentFlow()) payments.refundForDissolution(party.getId(),leavingRequestIds);
        EndReason survivor = party.getBoardedAt() != null ? EndReason.LEFT_ALONE : EndReason.PARTY_CANCELED;
        Instant nextSlot = AppClock.nextSlotAfter(now);
        for (RideRequest rider : riders) {
            Instant lastSlot = rider.resultBy();
            rider.clearParty();
            if (leavingRequestIds.contains(rider.getId())) {
                rider.finish(RequestStatus.CANCELED);
                continue;
            }
            if (nextSlot.isAfter(lastSlot)) {
                rider.setEndReason(survivor);
                rider.finish(RequestStatus.UNMATCHED);
            } else {
                rider.backToWaiting();
            }
            notifications.toMember(rider.getMember(), "[캠퍼스 택시메이트] 일행 취소",
                    "일행 중 한 명이 빠져 일행이 취소됐습니다. 앱에서 상태를 확인해 주세요.");
        }
    }
}
