package com.campustaximate.server.service;

import com.campustaximate.matching.ConfirmedGroup;
import com.campustaximate.matching.Direction;
import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.Hub;
import com.campustaximate.matching.MatchRequest;
import com.campustaximate.matching.MatchingRound;
import com.campustaximate.matching.MatchingSettings;
import com.campustaximate.matching.RoundResult;
import com.campustaximate.matching.TripPlan;
import com.campustaximate.server.common.ApiException;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 5분 슬롯마다 출발 처리와 매칭를 한다. 스케줄러와 시연 버튼은 같은 잠금을 쓴다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlotService {

    private static final Duration SETTLEMENT_LIMIT = Duration.ofHours(24);

    private final RideRequestRepository requests;
    private final PartyRepository parties;
    private final PartyShrinker shrinker;
    private final BoardingMonitor boarding;
    private final RoadLegStore legStore;
    private final SettlementService settlements;
    private final LoginTokenService loginTokens;
    private final RouteClient routes;
    private final RoutePathStore paths;
    private final NotificationService notifications;
    private final AppClock clock;
    private final MemberSafetyService safety;
    private final PlatformTransactionManager transactionManager;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<List<Long>, Instant> rejected = new ConcurrentHashMap<>();
    @Value("${app.public-url}")
    private String publicUrl;

    private final MatchingRound round = new MatchingRound(MatchingSettings.defaults());

    @Scheduled(cron = "0 */5 * * * *")
    public void onSchedule() {
        runLocked(() -> {
            process(AppClock.slotOf(clock.now()));
            return null;
        });
    }

    /** 등교 승차 판정은 5분 슬롯으로는 늦어서 1분마다 돈다. */
    @Scheduled(cron = "30 * * * * *")
    public void onMinute() {
        runLocked(() -> {
            boarding.check(clock.now());
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
    public void checkBoarding() {
        boarding.check(clock.now());
    }

    /** 잠금 안에서 부른다. */
    public void process(Instant slot) {
        List<RideRequest> waiting = requests.findByStatus(RequestStatus.WAITING);
        guarded("출발 처리", () -> settleDepartures(clock.now()));
        guarded("승차 판정", () -> boarding.check(clock.now()));
        guarded("보관 기간 삭제", settlements::purgeReceipts);
        guarded("로그인 토큰 정리", loginTokens::purgeExpired);
        matchWaiting(slot, waiting);
    }

    /** 부가 작업이 실패해도 매칭과 미성사 처리는 돌아야 한다. */
    private void guarded(String name, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            log.error("{} 실패. 다음 슬롯에서 다시 합니다.", name, e);
        }
    }

    private void settleDepartures(Instant now) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        for (Party candidate : parties.findByDirectionAndStatusAndDepartureAtLessThanEqual(
                Direction.FROM_HUB, PartyStatus.CONFIRMED, now)) {
            try {
                Set<Long> noShows = tx.execute(status -> closeOrCountNoShows(candidate.getId(), now));
                if (noShows != null && !noShows.isEmpty()) {
                    shrinker.remove(candidate.getId(), noShows, now, true);
                }
            } catch (RuntimeException e) {
                log.warn("일행 {} 출발 처리를 다음 슬롯으로 넘깁니다.", candidate.getId(), e);
            }
        }
    }

    /** 전원 도착이면 탑승 완료로 끝내고, 노쇼가 있으면 기록한 뒤 그 요청 번호를 돌려준다. */
    private Set<Long> closeOrCountNoShows(long partyId, Instant now) {
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
        Set<Long> noShows = new HashSet<>();
        for (RideRequest rider : riders) {
            if (!rider.isArrived()) {
                noShows.add(rider.getId());
                safety.recordNoShow(rider, now);
            }
        }
        if (noShows.isEmpty()) {
            riders.forEach(r -> r.finish(RequestStatus.COMPLETED));
        }
        return noShows;
    }

    private void matchWaiting(Instant slot, List<RideRequest> waiting) {
        Map<Long, RideRequest> snapshot = new LinkedHashMap<>();
        List<MatchRequest> input = new ArrayList<>();
        Map<Long, Set<Long>> avoidMap = safety.avoidMap(waiting.stream().map(r -> r.getMember().getId()).toList());
        for (RideRequest r : waiting) {
            snapshot.put(r.getId(), r);
            input.add(toEngine(r).withAvoidMemberIds(avoidMap.getOrDefault(r.getMember().getId(), Set.of())));
        }
        Instant now = clock.now();
        rejected.values().removeIf(until -> !until.isAfter(now));
        legStore.prepare(input.stream().filter(r -> TripPlan.of(r.direction()).eligible(r, slot)).toList(), now);
        Map<Long, Long> requestFares = new LinkedHashMap<>();
        snapshot.forEach((id, r) -> requestFares.put(id, r.getSoloFare()));
        RouteGroupVerifier verifier = new RouteGroupVerifier(routes, rejected, now, paths, requestFares);
        RoundResult result = round.run(slot, input, legStore.provider(), verifier);

        Instant limit = AppClock.nextSlotAfter(slot);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        for (ConfirmedGroup group : result.confirmed()) {
            if (isLate(limit, slot)) {
                return;
            }
            dropOnConflict(() -> tx.executeWithoutResult(
                    status -> confirm(group, verifier.routesOf(group.order()), snapshot)));
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

    private void confirm(ConfirmedGroup group, RouteGroupVerifier.VerifiedRoutes verified, Map<Long, RideRequest> snapshot) {
        List<Long> memberIds = group.order().stream().map(MatchRequest::memberId).toList();
        safety.lockMembers(memberIds);
        Map<Long, Set<Long>> currentAvoids = safety.avoidMap(memberIds);
        if (memberIds.stream().anyMatch(id -> memberIds.stream()
                .anyMatch(other -> currentAvoids.getOrDefault(id, Set.of()).contains(other)))) {
            return;
        }
        List<RideRequest> riders = new ArrayList<>();
        for (MatchRequest m : group.order()) {
            RideRequest current = requests.findById(m.id()).orElseThrow();
            if (current.getStatus() != RequestStatus.WAITING
                    || current.getVersion() != snapshot.get(m.id()).getVersion()) {
                log.info("요청 {}이 바뀌어 조합을 버립니다.", m.id());
                return;
            }
            riders.add(current);
        }
        List<Long> units = SettlementCalculator.ratioUnits(verified.solo().stream().map(RouteClient.Route::fare).toList());
        long total = verified.total();
        int payerIndex = group.order().indexOf(group.payer());
        List<Long> shares = SettlementCalculator.shares(total, units, payerIndex);
        RideRequest first = riders.get(0);
        Instant departure = group.departure() != null ? group.departure() : verified.departure();
        Party party = parties.save(new Party(first.getDirection(), first.getHub(), first.getSchool(),
                riders.get(payerIndex).getMember(), departure, total));
        safety.remember(party, riders);
        for (int i = 0; i < riders.size(); i++) {
            RideRequest rider = riders.get(i);
            rider.setSoloKm(verified.solo().get(i).km());
            rider.setSoloSeconds(verified.solo().get(i).seconds());
            rider.setSoloFare(verified.solo().get(i).fare());
            rider.setSharedSeconds(verified.rideSeconds().get(i));
            rider.setBoardAt(verified.boardAt() == null ? null : verified.boardAt().get(i));
            rider.setStatus(RequestStatus.MATCHED);
            rider.setParty(party);
            rider.setRideOrder(i + 1);
            rider.setRatioUnits(units.get(i));
            rider.setExpectedShare(shares.get(i));
            notifications.toMember(rider.getMember(), "[캠퍼스 택시메이트] 일행 확정",
                    "일행이 확정됐습니다. 출발 시각과 탑승 위치를 확인해 주세요: " + publicUrl);
        }
    }

    private void expire(MatchRequest expired, Map<Long, RideRequest> snapshot) {
        RideRequest current = requests.findById(expired.id()).orElseThrow();
        if (current.getStatus() != RequestStatus.WAITING
                || current.getVersion() != snapshot.get(expired.id()).getVersion()) {
            return;
        }
        current.finish(RequestStatus.UNMATCHED);
        notifications.toMember(current.getMember(), "[캠퍼스 택시메이트] 미성사",
                "이번 요청은 같이 탈 일행을 찾지 못했습니다.");
    }

    private static MatchRequest toEngine(RideRequest r) {
        if (r.getDirection() == Direction.TO_SCHOOL) {
            return MatchRequest.toSchool(r.getId(), r.getMember().getId(), r.getSchool().getId(),
                    new GeoPoint(r.getSchool().getLat(), r.getSchool().getLon()), r.getMember().getGender(),
                    r.getArriveBy(), r.getDeadlineSlot(), r.point(), r.getSoloKm(), r.getSoloSeconds());
        }
        Hub hub = new Hub(r.getHub().getId(), new GeoPoint(r.getHub().getLat(), r.getHub().getLon()));
        return new MatchRequest(r.getId(), r.getMember().getId(), hub, r.getMember().getGender(),
                r.getWindowStart(), r.getWindowEnd(), r.point(), r.getSoloKm(), r.getSoloSeconds());
    }
}
