package com.campustaximate.server.service;

import com.campustaximate.matching.Direction;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.PartyStatus;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.repository.PartyRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 등교 일행의 승차를 1분마다 판정한다. 각자 승차 시각 3분 뒤까지 도착 표시가 없으면 노쇼로 적고,
 * 결제자가 노쇼면 다음 승차자에게 넘긴다. 마지막 승차 시각 3분 뒤에 한 번만 일행을 마감한다.
 * 슬롯과 같은 잠금 안에서 부른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BoardingMonitor {

    private static final Duration SETTLEMENT_LIMIT = Duration.ofHours(24);

    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final PartyShrinker shrinker;
    private final NotificationService notifications;
    private final PlatformTransactionManager transactionManager;
    private final ReminderService reminders;

    public void check(Instant now) {
        try {
            reminders.remind(now);
        } catch (RuntimeException e) {
            log.warn("승차 안내를 다음 판정으로 넘깁니다.", e);
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        for (Party candidate : parties.findByDirectionAndStatusAndDepartureAtLessThanEqual(
                Direction.TO_SCHOOL, PartyStatus.CONFIRMED, now)) {
            try {
                Set<Long> leaving = tx.execute(status -> judge(candidate.getId(), now));
                if (leaving != null && !leaving.isEmpty()) {
                    shrinker.remove(candidate.getId(), leaving, now, true);
                }
            } catch (RuntimeException e) {
                log.warn("일행 {} 승차 마감을 다음 판정으로 넘깁니다.", candidate.getId(), e);
            }
        }
    }

    /** 마감 때 빠져야 할 요청 번호를 돌려준다. 아직 마감이 아니면 비어 있다. */
    private Set<Long> judge(long partyId, Instant now) {
        Party party = parties.findByIdForUpdate(partyId).orElseThrow();
        List<RideRequest> riders = requests.findByPartyOrderByRideOrder(party);
        if (party.getStatus() != PartyStatus.CONFIRMED) {
            return Set.of();
        }
        if (riders.stream().noneMatch(r -> r.getStatus() == RequestStatus.MATCHED)) {
            if (!party.getDepartureAt().plus(SETTLEMENT_LIMIT).isAfter(now)) {
                party.setStatus(PartyStatus.UNSETTLED);
            }
            return Set.of();
        }
        for (RideRequest rider : riders) {
            if (!rider.isArrived() && !rider.isNoShow() && now.isAfter(rider.arriveDeadline())) {
                rider.markNoShow();
                Member member = rider.getMember();
                member.setNoShowCount(member.getNoShowCount() + 1);
            }
        }
        handOverPayer(party, riders);
        Instant last = riders.stream().map(RideRequest::arriveDeadline).max(Instant::compareTo).orElseThrow();
        if (!now.isAfter(last)) {
            return Set.of();
        }
        Set<Long> leaving = new HashSet<>();
        riders.stream().filter(RideRequest::isNoShow).forEach(r -> leaving.add(r.getId()));
        if (leaving.isEmpty()) {
            riders.forEach(r -> r.finish(RequestStatus.COMPLETED));
        }
        return leaving;
    }

    /** 결제자가 노쇼면 다음 승차자가 택시를 잡는다. */
    private void handOverPayer(Party party, List<RideRequest> riders) {
        boolean payerGone = riders.stream()
                .anyMatch(r -> r.isNoShow() && r.getMember().getId().equals(party.getPayer().getId()));
        if (!payerGone) {
            return;
        }
        riders.stream().filter(r -> !r.isNoShow()).findFirst().ifPresent(next -> {
            party.setPayer(next.getMember());
            notifications.toMember(next.getMember(), "[캠퍼스 택시메이트] 결제자 변경",
                    "앞 승차자가 오지 않아 이제 내가 택시를 잡고 결제합니다. 앱에서 확인해 주세요.");
        });
    }
}
