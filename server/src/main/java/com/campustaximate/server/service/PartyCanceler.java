package com.campustaximate.server.service;

import com.campustaximate.server.common.AppClock;
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

    private static final long LEAD_SECONDS = 30 * 60L;

    private final RideRequestRepository requests;
    private final MailService mail;

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(Party party, Set<Long> leavingRequestIds, Instant now) {
        List<RideRequest> riders = requests.findByPartyOrderByDropoffOrder(party);
        party.setStatus(PartyStatus.CANCELED);
        Instant nextDeparture = AppClock.nextSlotAfter(now).plusSeconds(LEAD_SECONDS);
        for (RideRequest rider : riders) {
            rider.clearParty();
            if (leavingRequestIds.contains(rider.getId())) {
                rider.finish(RequestStatus.CANCELED);
                continue;
            }
            if (nextDeparture.isAfter(rider.getWindowEnd())) {
                rider.finish(RequestStatus.UNMATCHED);
            } else {
                rider.backToWaiting();
            }
            mail.send(rider.getMember().getEmail(), "[캠퍼스 택시메이트] 일행 취소",
                    "일행 중 한 명이 빠져 일행이 취소됐습니다. 앱에서 상태를 확인해 주세요.");
        }
    }
}
