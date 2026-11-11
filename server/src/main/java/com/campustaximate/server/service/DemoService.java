package com.campustaximate.server.service;

import com.campustaximate.matching.Direction;
import com.campustaximate.matching.GeoPoint;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Hub;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.domain.TransferStatus;
import com.campustaximate.server.repository.HubRepository;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 시연 모드. 시간 조작, 샘플 요청, 일행 만들기. */
@Service
@RequiredArgsConstructor
public class DemoService {

    private static final int MAX_MATCH_SLOTS = 600;
    private static final List<String> SAMPLE_MEMOS = List.of("흰 모자", "회색 후드", "검은 백팩");
    private static final List<Double> SAMPLE_RATIOS = List.of(0.45, 0.72, 0.3);

    private final SlotService slots;
    private final AppClock clock;
    private final RideRequestService rideRequests;
    private final RideRequestRepository requests;
    private final MemberRepository members;
    private final HubRepository hubs;
    private final PartyService partyService;
    private final SettlementService settlementService;
    private final PaymentService payments;
    private final com.campustaximate.server.repository.PaymentOrderRepository paymentOrders;
    private final WaitingInsightService insights;

    @Value("${app.demo.enabled}")
    private boolean enabled;

    public boolean enabled() {
        return enabled;
    }

    public record MatchOutcome(RequestStatus status, String message) {
    }

    /** 내 요청에 맞는 샘플을 만들고, 일행이 정해질 때까지 슬롯을 차례로 돌린다. */
    public MatchOutcome matchNow(long memberId) {
        requireEnabled();
        RideRequest mine = requests.findByActiveMemberId(memberId)
                .filter(r -> r.getStatus() == RequestStatus.WAITING)
                .orElseThrow(() -> ApiException.conflict("NO_WAITING_REQUEST", "탑승 요청을 먼저 만들어 주세요."));
        if (insights.of(mine.getId()).sameDirection() == 0) {
            addSamples(memberId, 2);
        }
        for (int i = 0; i < MAX_MATCH_SLOTS; i++) {
            advance(5);
            RequestStatus status = requests.findById(mine.getId()).orElseThrow().getStatus();
            if (status != RequestStatus.WAITING) {
                return new MatchOutcome(status, status == RequestStatus.MATCHED
                        ? "일행이 만들어졌습니다." : "조건이 맞는 일행이 없어 미성사로 끝났습니다.");
            }
        }
        return new MatchOutcome(RequestStatus.WAITING, "아직 일행이 정해지지 않았습니다.");
    }

    public void runNow() {
        requireEnabled();
        slots.runLocked(() -> {
            slots.process(AppClock.minuteOf(clock.now()));
            return null;
        });
    }

    /** 지나가는 슬롯을 모두 실행하며 시계를 옮긴다. */
    public void advance(int minutes) {
        requireEnabled();
        if (minutes <= 0 || minutes > 48 * 60) {
            throw ApiException.badRequest("INVALID_MINUTES", "1분에서 48시간 사이로 입력해 주세요.");
        }
        slots.runLocked(() -> {
            Instant target = clock.now().plusSeconds(minutes * 60L);
            Instant slot = AppClock.nextMinuteAfter(clock.now());
            while (!slot.isAfter(target)) {
                clock.moveTo(slot);
                slots.process(slot);
                slot = AppClock.nextMinuteAfter(slot);
            }
            clock.moveTo(target);
            slots.checkBoarding();
            return null;
        });
    }

    /** 로그인한 사람과 같은 성별의 시연 계정으로 샘플 요청을 만든다. */
    public int addSamples(long memberId, int count) {
        requireEnabled();
        Member me = members.findById(memberId).orElseThrow(ApiException::unauthorized);
        Hub hub = hubs.findBySchool(me.getSchool().getId()).get(0);
        RideRequest mine = requests.findByActiveMemberId(memberId).orElse(null);
        Direction direction = mine == null ? Direction.FROM_HUB : mine.getDirection();
        Instant arriveBy = mine == null ? null : mine.getArriveBy();
        boolean sameWindow = mine != null && mine.getWindowStart() != null;
        Instant start = sameWindow ? mine.getWindowStart() : AppClock.nextSlotAfter(clock.now()).plusSeconds(30 * 60L);
        Instant end = sameWindow ? mine.getWindowEnd() : start.plusSeconds(60 * 60L);
        List<Member> samples = members.findAll().stream()
                .filter(m -> DataSeeder.isSeededDemo(m.getEmail()))
                .filter(m -> m.getGender() == me.getGender() && !m.getId().equals(memberId))
                .filter(m -> requests.findByActiveMemberId(m.getId()).isEmpty())
                .filter(m -> !rideRequests.isRestricted(m.getId()))
                .limit(count)
                .toList();
        for (int i = 0; i < samples.size(); i++) {
            DataSeeder.Dropoff dropoff = DataSeeder.SAMPLE_DROPOFFS.get(i % DataSeeder.SAMPLE_DROPOFFS.size());
            String memo = SAMPLE_MEMOS.get(i % SAMPLE_MEMOS.size());
            GeoPoint onTheWay = onTheWay(mine, hub, SAMPLE_RATIOS.get(i % SAMPLE_RATIOS.size()));
            long id = samples.get(i).getId();
            try {
                rideRequests.create(id, new RideRequestService.NewRequest(direction, hub.getId(), start, end, arriveBy,
                        dropoff.name(), onTheWay == null ? dropoff.lat() : onTheWay.lat(),
                        onTheWay == null ? dropoff.lon() : onTheWay.lon(), memo));
            } catch (ApiException e) {
                if (onTheWay == null) {
                    throw e;
                }
                rideRequests.create(id, new RideRequestService.NewRequest(direction, hub.getId(), start, end, arriveBy,
                        dropoff.name(), dropoff.lat(), dropoff.lon(), memo));
            }
        }
        return samples.size();
    }

    /** 샘플은 내 경로 위에 둔다. 다른 방향에 두면 시연에서 일행이 만들어지지 않는다. */
    private static GeoPoint onTheWay(RideRequest mine, Hub hub, double ratio) {
        if (mine == null) {
            return null;
        }
        GeoPoint to = mine.point();
        double fromLat = mine.getHub() != null ? hub.getLat() : mine.getSchool().getLat();
        double fromLon = mine.getHub() != null ? hub.getLon() : mine.getSchool().getLon();
        return new GeoPoint(fromLat + (to.lat() - fromLat) * ratio, fromLon + (to.lon() - fromLon) * ratio);
    }

    /** 내 일행의 샘플 계정을 도착, 승차, 결제, 보냄으로 표시한다. 일반 회원과 같은 경로를 탄다. */
    public void actAsSamples(long memberId) {
        requireEnabled();
        for (PartyService.PartyView party : partyService.openParties(memberId)) {
            if (payments.simulated()) {
                for (var order : paymentOrders.findByPartyIdOrderById(party.id())) {
                    if (isSample(order.getMemberId(), memberId) && order.getStatus() == com.campustaximate.server.domain.PaymentStatus.REQUIRED) {
                        payments.confirm(order.getMemberId(), order.getOrderId(), "demo_" + order.getOrderId(), order.getAmount());
                    }
                }
            }
            for (PartyService.MemberView rider : party.members()) {
                if (!isSample(rider.memberId(), memberId) || rider.arrived() || party.settlement() != null
                        || rider.noShow() || clock.now().isAfter(rider.arriveDeadline())) {
                    continue;
                }
                if (party.direction() == Direction.TO_SCHOOL && rider.memberId() == party.payerId()) {
                    partyService.board(rider.memberId(), party.id());
                } else {
                    partyService.arrive(rider.memberId(), party.id());
                }
            }
            PartyService.PartyView payerView = isSample(party.payerId(), memberId)
                    ? partyService.view(party.payerId(), party.id()) : null;
            if (payerView != null && payerView.canSettle()) {
                settlementService.submitForDemo(party.payerId(), party.id(), party.expectedTotal(),
                        "OO은행", "000-0000-0000");
            }
            PartyService.PartyView current = partyService.view(memberId, party.id());
            if (current.settlement() == null) {
                continue;
            }
            for (PartyService.TransferView transfer : current.settlement().transfers()) {
                if (isSample(transfer.senderId(), memberId) && transfer.status() == TransferStatus.REQUESTED) {
                    settlementService.markSent(transfer.senderId(), transfer.id(), current.settlement().revision());
                }
            }
            if (isSample(party.payerId(), memberId)) {
                partyService.view(party.payerId(), party.id()).settlement().transfers().stream()
                        .filter(transfer -> transfer.status() == TransferStatus.SENT)
                        .forEach(transfer -> settlementService.markReceived(party.payerId(), transfer.id()));
            }
        }
    }

    private boolean isSample(long id, long memberId) {
        return id != memberId && members.findById(id).map(m -> DataSeeder.isSeededDemo(m.getEmail())).orElse(false);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw ApiException.notFound("시연 모드가 꺼져 있습니다.");
        }
    }
}
