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
    private static final List<String> SAMPLE_MEMOS = List.of("흰 모자 썼어요", "회색 후드티 입었어요", "검은 백팩 멨어요");
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
    private final DemoRouteSelector demoRoutes;
    private final DemoChat chat;
    private final org.springframework.core.env.Environment environment;

    @Value("${app.demo.enabled}")
    private boolean enabled;

    public boolean enabled() {
        return enabled && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("demo"));
    }

    public record MatchOutcome(RequestStatus status, String message) {
    }

    /** 내 요청에 맞는 샘플을 만들고, 일행이 정해질 때까지 슬롯을 차례로 돌린다. */
    public enum Role { PAYER, PASSENGER }

    public MatchOutcome matchNow(long memberId) { return matchNow(memberId, Role.PAYER); }

    public MatchOutcome matchNow(long memberId, Role role) {
        requireEnabled();
        RideRequest mine = requests.findByActiveMemberId(memberId)
                .filter(r -> r.getStatus() == RequestStatus.WAITING)
                .orElseThrow(() -> ApiException.conflict("NO_WAITING_REQUEST", "탑승 요청을 먼저 만들어 주세요."));
        if (insights.of(mine.getId()).sameDirection() == 0) {
            addSamples(memberId, 2, role);
        }
        for (int i = 0; i < MAX_MATCH_SLOTS; i++) {
            advance(mine.getMode()==com.campustaximate.server.domain.RequestMode.INSTANT?1:5);
            RequestStatus status = requests.findById(mine.getId()).orElseThrow().getStatus();
            if (status != RequestStatus.WAITING) {
                if(status==RequestStatus.MATCHED) {
                    var party=partyService.openParties(memberId).getFirst();
                    sampleChat(memberId,party,DemoTalk.greet(party.id()));
                    if(party.payer()!=(role==Role.PAYER)) throw ApiException.conflict("DEMO_ROLE_MISMATCH","기존 대기 요청과 매칭되어 선택한 역할과 달라요. 현재 일행의 역할을 확인해 주세요.");
                    return new MatchOutcome(status,party.payer()?"일행이 만들어졌어요. 내가 결제자예요.":"일행이 만들어졌어요. 나는 일행원이에요. 선결제를 체험해 보세요.");
                }
                return new MatchOutcome(status,"조건이 맞는 일행이 없어 미성사로 끝났습니다.");
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

    /**
     * 홈 "지금 모집 중"을 시연한다. 로그인한 사람은 빼고, 같은 성별 시연 계정 두 명이 일행을 만든다.
     * 로그인한 사람은 게시판에서 이 일행에 합류해 볼 수 있다.
     */
    public String openBoardParty(long viewerId) {
        requireEnabled();
        Member viewer = members.findById(viewerId).orElseThrow(ApiException::unauthorized);
        if (requests.findByActiveMemberId(viewerId).isPresent()) throw ApiException.conflict("DEMO_FINISH_REQUEST", "진행 중인 요청이 없을 때 만들어 주세요.");
        List<Member> pool = members.findAll().stream().filter(m -> DataSeeder.isSeededDemo(m.getEmail()))
                .filter(m -> m.getGender() == viewer.getGender() && !m.getId().equals(viewerId))
                .filter(m -> requests.findByActiveMemberId(m.getId()).isEmpty() && !rideRequests.isRestricted(m.getId())).toList();
        if (pool.size() < 2) throw ApiException.conflict("DEMO_NO_SAMPLES", "모집 중 일행을 만들 같은 성별 시연 계정이 부족해요.");
        Member host = pool.getFirst();
        Hub hub = hubs.findBySchool(viewer.getSchool().getId()).stream().filter(h -> h.isActive() && h.isFieldChecked()).findFirst()
                .orElseThrow(() -> ApiException.conflict("DEMO_NO_HUB", "확인된 승차 지점이 필요해요."));
        Instant start = AppClock.nextSlotAfter(clock.now()).plusSeconds(60 * 60L);
        DataSeeder.Dropoff stop = DataSeeder.SAMPLE_DROPOFFS.get(3);
        RideRequest hostRequest = rideRequests.create(host.getId(), new RideRequestService.NewRequest(Direction.FROM_HUB, hub.getId(), start,
                start.plusSeconds(60 * 60L), null, stop.name(), stop.lat(), stop.lon(), SAMPLE_MEMOS.get(2)));
        addSamples(host.getId(), 1, Role.PAYER, java.util.Set.of(viewerId));
        for (int i = 0; i < MAX_MATCH_SLOTS; i++) {
            advance(5);
            RequestStatus status = requests.findById(hostRequest.getId()).orElseThrow().getStatus();
            if (status == RequestStatus.MATCHED) {
                var party = partyService.openParties(host.getId()).getFirst();
                chat.send(host.getId(), party.id(), DemoTalk.greet(host.getId()));
                return "같은 방향 일행이 모집 중이에요. 홈에서 합류해 보세요.";
            }
            if (status != RequestStatus.WAITING) break;
        }
        throw ApiException.conflict("DEMO_NOT_MATCHED", "샘플 일행을 만들지 못했어요. 다시 시도해 주세요.");
    }

    /** 로그인한 사람과 같은 성별의 시연 계정으로 샘플 요청을 만든다. */
    public int addSamples(long memberId, int count) { return addSamples(memberId,count,Role.PAYER); }

    public int addSamples(long memberId, int count, Role role) { return addSamples(memberId, count, role, java.util.Set.of()); }

    public int addSamples(long memberId, int count, Role role, java.util.Set<Long> exclude) {
        requireEnabled();
        if(count<1 || count>3) throw ApiException.badRequest("INVALID_COUNT","샘플은 1~3명만 만들 수 있어요.");
        Member me = members.findById(memberId).orElseThrow(ApiException::unauthorized);
        Hub hub = hubs.findBySchool(me.getSchool().getId()).get(0);
        RideRequest mine = requests.findByActiveMemberId(memberId).flatMap(r->requests.findById(r.getId())).orElse(null);
        if(mine!=null && mine.getHub()!=null)hub=mine.getHub();
        Direction direction = mine == null ? Direction.FROM_HUB : mine.getDirection();
        Instant arriveBy = mine == null ? null : mine.getArriveBy();
        boolean sameWindow = mine != null && mine.getWindowStart() != null;
        Instant start = sameWindow ? mine.getWindowStart() : AppClock.nextSlotAfter(clock.now()).plusSeconds(30 * 60L);
        Instant end = sameWindow ? mine.getWindowEnd() : start.plusSeconds(60 * 60L);
        List<Member> samples = members.findAll().stream()
                .filter(m -> DataSeeder.isSeededDemo(m.getEmail()))
                .filter(m -> m.getGender() == me.getGender() && !m.getId().equals(memberId) && !exclude.contains(m.getId()))
                .filter(m -> requests.findByActiveMemberId(m.getId()).isEmpty())
                .filter(m -> !rideRequests.isRestricted(m.getId()))
                .limit(count)
                .toList();
        if(role==Role.PASSENGER && direction!=Direction.FROM_HUB) throw ApiException.conflict("DEMO_COMMON_PICKUP_REQUIRED","공동 승차 요청에서 선결제를 체험해 주세요.");
        if(role==Role.PASSENGER && samples.isEmpty()) throw ApiException.conflict("DEMO_NO_SAMPLES","사용 가능한 시연 계정이 없어요. 다른 계정의 진행 중 요청을 먼저 마쳐 주세요.");
        List<GeoPoint> checked=mine!=null && direction==Direction.FROM_HUB && (demoRoutes.realRoutes() || role==Role.PASSENGER)
                ?demoRoutes.select(SlotService.toEngine(mine).stream().filter(m->m.anchorId()==mine.getHub().getId()).findFirst().orElseThrow(),
                    mine.getSoloFare(),mine.origin(),samples.stream().map(Member::getId).toList(),role==Role.PASSENGER):List.of();
        for (int i = 0; i < samples.size(); i++) {
            DataSeeder.Dropoff dropoff = DataSeeder.SAMPLE_DROPOFFS.get(i % DataSeeder.SAMPLE_DROPOFFS.size());
            String memo = SAMPLE_MEMOS.get(i % SAMPLE_MEMOS.size());
            GeoPoint onTheWay = checked.isEmpty()?onTheWay(mine, hub, SAMPLE_RATIOS.get(i % SAMPLE_RATIOS.size())):checked.get(i);
            long id = samples.get(i).getId();
            try {
                rideRequests.create(id, new RideRequestService.NewRequest(direction, hub.getId(), start, end, arriveBy,
                        dropoff.name(), onTheWay == null ? dropoff.lat() : onTheWay.lat(),
                        onTheWay == null ? dropoff.lon() : onTheWay.lon(), memo,
                        mine==null?null:mine.getMode(),mine==null?null:mine.getTimeBasis(),null,null,mine==null?null:mine.getEarlyArrivalMinutes()));
            } catch (ApiException e) {
                if (onTheWay == null || !checked.isEmpty()) {
                    throw e;
                }
                rideRequests.create(id, new RideRequestService.NewRequest(direction, hub.getId(), start, end, arriveBy,
                        dropoff.name(), dropoff.lat(), dropoff.lon(), memo,
                        mine==null?null:mine.getMode(),mine==null?null:mine.getTimeBasis(),null,null,mine==null?null:mine.getEarlyArrivalMinutes()));
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
                        chat.send(order.getMemberId(),party.id(),DemoTalk.paid(order.getMemberId()));
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
                    chat.send(rider.memberId(),party.id(),DemoTalk.arrived(rider.memberId(),rider.memo()));
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

    public void sampleChat(long memberId,PartyService.PartyView party,String text) {
        requireEnabled();
        party.members().stream().filter(m->isSample(m.memberId(),memberId)).findFirst().ifPresent(m->chat.send(m.memberId(),party.id(),text));
    }

    private boolean isSample(long id, long memberId) {
        return id != memberId && members.findById(id).map(m -> DataSeeder.isSeededDemo(m.getEmail())).orElse(false);
    }

    public void requireEnabled() {
        if (!enabled()) {
            throw ApiException.notFound("시연 모드가 꺼져 있습니다.");
        }
    }
}
