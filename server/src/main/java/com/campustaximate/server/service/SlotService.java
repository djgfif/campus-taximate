package com.campustaximate.server.service;

import com.campustaximate.matching.ConfirmedGroup;
import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.Hub;
import com.campustaximate.matching.MatchRequest;
import com.campustaximate.matching.MatchingRound;
import com.campustaximate.matching.MatchingSettings;
import com.campustaximate.matching.RoundResult;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.PartyStatus;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.repository.PartyRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.route.RouteClient;
import com.campustaximate.settlement.SettlementCalculator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 5분 슬롯마다 출발 처리와 묶기를 한다. 스케줄러와 시연 버튼은 같은 잠금을 쓴다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlotService {

    private final RideRequestRepository requests;
    private final PartyRepository parties;
    private final PartyCanceler canceler;
    private final RouteClient routes;
    private final MailService mail;
    private final AppClock clock;
    private final PlatformTransactionManager transactionManager;
    private final ReentrantLock lock = new ReentrantLock();
    private final MatchingRound round = new MatchingRound(MatchingSettings.defaults());

    @Scheduled(cron = "0 */5 * * * *")
    public void onSchedule() {
        runLocked(() -> {
            process(AppClock.slotOf(clock.now()));
            return null;
        });
    }

    public <T> T runLocked(Supplier<T> work) {
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    /** 잠금 안에서 부른다. */
    public void process(Instant slot) {
        List<RideRequest> waiting = requests.findByStatus(RequestStatus.WAITING);
        settleDepartures(clock.now());
        matchWaiting(slot, waiting);
    }

    private void settleDepartures(Instant now) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        for (Party candidate : parties.findByStatusAndDepartureAtLessThanEqual(PartyStatus.CONFIRMED, now)) {
            tx.executeWithoutResult(status -> {
                Party party = parties.findByIdForUpdate(candidate.getId()).orElseThrow();
                List<RideRequest> riders = requests.findByPartyOrderByDropoffOrder(party);
                if (party.getStatus() != PartyStatus.CONFIRMED
                        || riders.stream().noneMatch(r -> r.getStatus() == RequestStatus.MATCHED)) {
                    return;
                }
                Set<Long> noShows = new HashSet<>();
                for (RideRequest rider : riders) {
                    if (!rider.isArrived()) {
                        noShows.add(rider.getId());
                        Member member = rider.getMember();
                        member.setNoShowCount(member.getNoShowCount() + 1);
                    }
                }
                if (noShows.isEmpty()) {
                    riders.forEach(r -> r.finish(RequestStatus.COMPLETED));
                } else {
                    canceler.cancel(party, noShows, now);
                }
            });
        }
    }

    private void matchWaiting(Instant slot, List<RideRequest> waiting) {
        Map<Long, RideRequest> snapshot = new LinkedHashMap<>();
        List<MatchRequest> input = new ArrayList<>();
        for (RideRequest r : waiting) {
            snapshot.put(r.getId(), r);
            input.add(toEngine(r));
        }
        RouteGroupVerifier verifier = new RouteGroupVerifier(routes, snapshot);
        RoundResult result = round.run(slot, input, verifier);

        Instant limit = AppClock.nextSlotAfter(slot);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        for (ConfirmedGroup group : result.confirmed()) {
            if (isLate(limit, slot)) {
                return;
            }
            dropOnConflict(() -> tx.executeWithoutResult(
                    status -> confirm(group, verifier.faresOf(group.dropoffOrder()), snapshot)));
        }
        for (MatchRequest expired : result.expired()) {
            if (isLate(limit, slot)) {
                return;
            }
            dropOnConflict(() -> tx.executeWithoutResult(status -> expire(expired, snapshot)));
        }
    }

    private boolean isLate(Instant limit, Instant slot) {
        if (clock.now().isBefore(limit)) {
            return false;
        }
        log.warn("슬롯 {} 계산이 다음 슬롯을 넘겨 남은 결과를 버립니다.", slot);
        return true;
    }

    /** 저장 중 취소와 겹치면 그 조합만 버리고 다음으로 넘어간다. */
    private void dropOnConflict(Runnable save) {
        try {
            save.run();
        } catch (OptimisticLockingFailureException e) {
            log.info("다른 변경과 겹쳐 결과 하나를 버립니다.");
        }
    }

    private void confirm(ConfirmedGroup group, List<Long> fares, Map<Long, RideRequest> snapshot) {
        List<RideRequest> riders = new ArrayList<>();
        for (MatchRequest m : group.dropoffOrder()) {
            RideRequest current = requests.findById(m.id()).orElseThrow();
            if (current.getStatus() != RequestStatus.WAITING
                    || current.getVersion() != snapshot.get(m.id()).getVersion()) {
                log.info("요청 {}이 바뀌어 조합을 버립니다.", m.id());
                return;
            }
            riders.add(current);
        }
        List<Long> units = SettlementCalculator.ratioUnits(fares);
        long total = fares.get(fares.size() - 1);
        List<Long> shares = SettlementCalculator.shares(total, units);
        RideRequest last = riders.get(riders.size() - 1);
        Party party = parties.save(new Party(last.getHub(), last.getMember(), group.departure(), total));
        for (int i = 0; i < riders.size(); i++) {
            RideRequest rider = riders.get(i);
            rider.setStatus(RequestStatus.MATCHED);
            rider.setParty(party);
            rider.setDropoffOrder(i + 1);
            rider.setRatioUnits(units.get(i));
            rider.setExpectedShare(shares.get(i));
            mail.send(rider.getMember().getEmail(), "[캠퍼스 택시메이트] 일행 확정",
                    "일행이 확정됐습니다. 앱에서 출발 시각과 일행을 확인해 주세요.");
        }
    }

    private void expire(MatchRequest expired, Map<Long, RideRequest> snapshot) {
        RideRequest current = requests.findById(expired.id()).orElseThrow();
        if (current.getStatus() != RequestStatus.WAITING
                || current.getVersion() != snapshot.get(expired.id()).getVersion()) {
            return;
        }
        current.finish(RequestStatus.UNMATCHED);
        mail.send(current.getMember().getEmail(), "[캠퍼스 택시메이트] 미성사",
                "이번 요청은 같이 탈 일행을 찾지 못했습니다.");
    }

    private static MatchRequest toEngine(RideRequest r) {
        Hub hub = new Hub(r.getHub().getId(), new GeoPoint(r.getHub().getLat(), r.getHub().getLon()));
        return new MatchRequest(r.getId(), r.getMember().getId(), hub, r.getMember().getGender(),
                r.getWindowStart(), r.getWindowEnd(), new GeoPoint(r.getDropoffLat(), r.getDropoffLon()));
    }
}
