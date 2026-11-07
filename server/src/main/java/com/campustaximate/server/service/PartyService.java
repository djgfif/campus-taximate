package com.campustaximate.server.service;

import com.campustaximate.server.common.ApiException;
import com.campustaximate.matching.Direction;
import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.TripPath;
import com.campustaximate.matching.TripPlan;
import com.campustaximate.server.route.RouteClient;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.PartyStatus;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.domain.Settlement;
import com.campustaximate.server.domain.TransferRequest;
import com.campustaximate.server.domain.TransferStatus;
import com.campustaximate.server.repository.PartyRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.repository.SettlementRepository;
import com.campustaximate.server.repository.TransferRequestRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PartyService {

    private static final double WARNING_RATE = 0.15;
    private static final Duration CALL_BEFORE = Duration.ofMinutes(10);

    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final SettlementRepository settlements;
    private final TransferRequestRepository transfers;
    private final AppClock clock;
    private final RoutePathStore paths;

    public record MemberView(long requestId, long memberId, String nickname, String memo, int rideOrder,
                             String pointName, long expectedShare, boolean arrived, boolean noShow,
                             long soloFare, int soloSeconds, int sharedSeconds, Instant boardAt,
                             Instant arriveDeadline) {
    }

    public record TransferView(long id, long senderId, String senderNickname, long amount, TransferStatus status) {
    }

    /** 계좌는 일행에게만 내려가고, 지운 뒤에는 비어 있다. */
    public record SettlementView(long paidAmount, int revision, boolean warning, int differencePercent,
                                 boolean canCorrect, boolean receiptAvailable, String bankName, String accountNumber,
                                 List<TransferView> transfers) {
    }

    public record PartyView(long id, Direction direction, PartyStatus status, String anchorName, String meetingPoint,
                            Instant departureAt, Instant boardedAt, Instant myBoardAt, Instant arrivalAt,
                            Instant callAt, Instant arriveDeadline, long expectedTotal, long soloTotal,
                            long payerId, String payerNickname, boolean payer, boolean canArrive, boolean canBoard,
                            boolean canCancel, boolean canSettle, long myRequestId, long myShare, long mySoloFare,
                            List<MemberView> members, SettlementView settlement) {
    }

    @Transactional
    public void arrive(long memberId, long partyId) {
        Party party = parties.findByIdForUpdate(partyId).orElseThrow(() -> ApiException.notFound("없는 일행입니다."));
        RideRequest mine = mineOf(party, memberId);
        if (!canMarkArrival(mine)) {
            throw ApiException.conflict("BAD_STATE", "도착을 표시할 수 없는 상태입니다.");
        }
        mine.setArrived(true);
    }

    /** 등교 결제자의 "택시 탔어요". 도착 표시를 겸하고 다음 승차자에게 남은 시간을 보여 준다. */
    @Transactional
    public void board(long memberId, long partyId) {
        Party party = parties.findByIdForUpdate(partyId).orElseThrow(() -> ApiException.notFound("없는 일행입니다."));
        RideRequest mine = mineOf(party, memberId);
        if (party.getDirection() != Direction.TO_SCHOOL || !party.getPayer().getId().equals(memberId)) {
            throw ApiException.forbidden("등교 결제자만 표시할 수 있습니다.");
        }
        if (!canMarkBoarding(mine)) {
            throw ApiException.conflict("BAD_STATE", "승차를 표시할 수 없는 상태입니다.");
        }
        mine.setArrived(true);
        if (party.getBoardedAt() == null) {
            party.setBoardedAt(clock.now());
        }
    }

    private RideRequest mineOf(Party party, long memberId) {
        return requests.findByPartyOrderByRideOrder(party).stream()
                .filter(r -> r.getMember().getId() == memberId)
                .findFirst()
                .orElseThrow(() -> ApiException.forbidden("내 일행이 아닙니다."));
    }

    private boolean canMarkArrival(RideRequest rider) {
        return rider.getStatus() == RequestStatus.MATCHED && !rider.isNoShow()
                && !clock.now().isAfter(rider.arriveDeadline());
    }

    /** 앞 승차자가 빠져 결제자가 된 사람은 이미 도착했으면 자기 시각이 지나도 탈 수 있다. */
    private boolean canMarkBoarding(RideRequest rider) {
        return rider.getStatus() == RequestStatus.MATCHED && !rider.isNoShow()
                && (rider.isArrived() || !clock.now().isAfter(rider.arriveDeadline()));
    }

    @Transactional(readOnly = true)
    public PartyView view(long memberId, long partyId) {
        Party party = parties.findById(partyId).orElseThrow(() -> ApiException.notFound("없는 일행입니다."));
        return toView(memberId, party);
    }

    @Transactional(readOnly = true)
    public List<PartyView> openParties(long memberId) {
        return requests.findOpenByMember(memberId, List.of(PartyStatus.CONFIRMED, PartyStatus.SETTLING)).stream()
                .map(r -> toView(memberId, r.getParty()))
                .toList();
    }

    /** 일행만 사진을 볼 수 있다. */
    @Transactional(readOnly = true)
    public Settlement receiptOf(long memberId, long partyId) {
        Party party = parties.findById(partyId).orElseThrow(() -> ApiException.notFound("없는 일행입니다."));
        boolean mine = requests.findByPartyOrderByRideOrder(party).stream()
                .anyMatch(r -> r.getMember().getId() == memberId);
        if (!mine) {
            throw ApiException.forbidden("내 일행이 아닙니다.");
        }
        return settlements.findByParty(party).orElseThrow(() -> ApiException.notFound("정산이 없습니다."));
    }

    public record RouteView(List<GeoPoint> shared, List<GeoPoint> solo) {
    }

    public record RoutePlan(TripPath shared, TripPath solo) {
    }

    /** 경로 조회 대상만 읽는다. 카카오 호출은 트랜잭션 밖에서 routeView로 한다. */
    @Transactional(readOnly = true)
    public RoutePlan routePlan(long memberId, long partyId) {
        Party party = parties.findById(partyId).orElseThrow(() -> ApiException.notFound("없는 일행입니다."));
        List<RideRequest> riders = requests.findByPartyOrderByRideOrder(party);
        RideRequest mine = riders.stream().filter(r -> r.getMember().getId() == memberId).findFirst()
                .orElseThrow(() -> ApiException.forbidden("내 일행이 아닙니다."));
        TripPlan plan = TripPlan.of(party.getDirection());
        GeoPoint anchor = party.anchorPoint();
        return new RoutePlan(plan.sharedPath(anchor, riders.stream().map(RideRequest::point).toList()),
                plan.soloPath(anchor, mine.point()));
    }

    public RouteView routeView(RoutePlan plan) {
        try {
            return new RouteView(paths.coordinates(plan.shared().origin(), plan.shared().stops()),
                    paths.coordinates(plan.solo().origin(), plan.solo().stops()));
        } catch (RouteClient.RouteUnavailableException e) {
            return new RouteView(List.of(), List.of());
        }
    }

    private PartyView toView(long memberId, Party party) {
        List<RideRequest> riders = requests.findByPartyOrderByRideOrder(party);
        RideRequest mine = riders.stream()
                .filter(r -> r.getMember().getId() == memberId)
                .findFirst()
                .orElseThrow(() -> ApiException.forbidden("내 일행이 아닙니다."));
        boolean payer = party.getPayer().getId() == memberId;
        boolean toSchool = party.getDirection() == Direction.TO_SCHOOL;
        boolean boarded = riders.stream().allMatch(r -> r.getStatus() == RequestStatus.COMPLETED);
        SettlementView settlement = settlements.findByParty(party).map(s -> toView(s, party, payer)).orElse(null);
        List<MemberView> members = riders.stream()
                .map(r -> new MemberView(r.getId(), r.getMember().getId(), r.getMember().getNickname(), r.getMemo(),
                        r.getRideOrder(), r.getPointName(), r.getExpectedShare(), r.isArrived(), r.isNoShow(),
                        r.getSoloFare(), r.getSoloSeconds(), r.getSharedSeconds(), r.getBoardAt(), r.arriveDeadline()))
                .toList();
        Instant myStart = mine.getBoardAt() != null ? mine.getBoardAt() : party.getDepartureAt();
        Instant payerBoardAt = riders.stream()
                .filter(r -> r.getMember().getId().equals(party.getPayer().getId()))
                .findFirst().map(RideRequest::getBoardAt).orElse(null);
        long soloTotal = riders.stream().mapToLong(RideRequest::getSoloFare).sum();
        return new PartyView(party.getId(), party.getDirection(), party.getStatus(), party.anchorName(),
                party.getHub() != null ? party.getHub().getMeetingPoint() : mine.getPointName(),
                party.getDepartureAt(), party.getBoardedAt(), mine.getBoardAt(),
                myStart.plusSeconds(mine.getSharedSeconds()),
                payerBoardAt == null ? null : payerBoardAt.minus(CALL_BEFORE), mine.arriveDeadline(),
                party.getExpectedTotal(), soloTotal, party.getPayer().getId(), party.getPayer().getNickname(), payer,
                !mine.isArrived() && canMarkArrival(mine),
                toSchool && payer && party.getBoardedAt() == null && canMarkBoarding(mine),
                mine.canCancelAt(clock.now()),
                payer && boarded && settlement == null, mine.getId(), mine.getExpectedShare(), mine.getSoloFare(),
                members, settlement);
    }

    private SettlementView toView(Settlement settlement, Party party, boolean payer) {
        List<TransferRequest> list = transfers.findBySettlementOrderById(settlement);
        long expected = party.getExpectedTotal();
        long difference = settlement.getPaidAmount() - expected;
        boolean warning = Math.abs(difference) > expected * WARNING_RATE;
        int percent = expected == 0 ? 0 : (int) Math.round(difference * 100.0 / expected);
        boolean canCorrect = payer && list.stream().allMatch(t -> t.getStatus() == TransferStatus.REQUESTED);
        List<TransferView> views = list.stream()
                .map(t -> new TransferView(t.getId(), t.getSender().getId(), t.getSender().getNickname(),
                        t.getAmount(), t.getStatus()))
                .toList();
        return new SettlementView(settlement.getPaidAmount(), settlement.getRevision(), warning, percent, canCorrect,
                settlement.getReceiptPath() != null, settlement.getBankName(), settlement.getAccountNumber(), views);
    }
}
