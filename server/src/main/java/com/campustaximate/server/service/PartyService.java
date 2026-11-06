package com.campustaximate.server.service;

import com.campustaximate.server.common.ApiException;
import com.campustaximate.matching.GeoPoint;
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
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PartyService {

    private static final double WARNING_RATE = 0.15;

    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final SettlementRepository settlements;
    private final TransferRequestRepository transfers;
    private final AppClock clock;
    private final RoutePathStore paths;

    public record MemberView(long requestId, long memberId, String nickname, String memo, int dropoffOrder,
                             String dropoffName, long expectedShare, boolean arrived,
                             long soloFare, int soloSeconds, int sharedSeconds) {
    }

    public record TransferView(long id, long senderId, String senderNickname, long amount, TransferStatus status) {
    }

    public record SettlementView(long paidAmount, int revision, boolean warning, boolean canCorrect,
                                 boolean receiptAvailable, List<TransferView> transfers) {
    }

    public record PartyView(long id, PartyStatus status, String hubName, String meetingPoint, Instant departureAt,
                            long expectedTotal,
                            long payerId, String payerNickname, boolean payer, boolean canArrive, boolean canCancel,
                            boolean canSettle, long myRequestId, List<MemberView> members,
                            SettlementView settlement) {
    }

    @Transactional
    public void arrive(long memberId, long partyId) {
        Party party = parties.findByIdForUpdate(partyId).orElseThrow(() -> ApiException.notFound("없는 일행입니다."));
        RideRequest mine = requests.findByPartyOrderByDropoffOrder(party).stream()
                .filter(r -> r.getMember().getId() == memberId)
                .findFirst()
                .orElseThrow(() -> ApiException.forbidden("내 일행이 아닙니다."));
        if (mine.getStatus() != RequestStatus.MATCHED || clock.now().isAfter(party.getDepartureAt())) {
            throw ApiException.conflict("BAD_STATE", "도착을 표시할 수 없는 상태입니다.");
        }
        mine.setArrived(true);
    }

    @Transactional(readOnly = true)
    public PartyView view(long memberId, long partyId) {
        Party party = parties.findById(partyId).orElseThrow(() -> ApiException.notFound("없는 일행입니다."));
        return toView(memberId, party);
    }

    @Transactional(readOnly = true)
    public List<PartyView> openParties(long memberId) {
        return requests.findByMemberIdOrderByIdDesc(memberId).stream()
                .map(RideRequest::getParty)
                .filter(p -> p != null && (p.getStatus() == PartyStatus.CONFIRMED
                        || p.getStatus() == PartyStatus.SETTLING))
                .map(p -> toView(memberId, p))
                .toList();
    }

    /** 일행만 사진을 볼 수 있다. */
    @Transactional(readOnly = true)
    public Settlement receiptOf(long memberId, long partyId) {
        Party party = parties.findById(partyId).orElseThrow(() -> ApiException.notFound("없는 일행입니다."));
        boolean mine = requests.findByPartyOrderByDropoffOrder(party).stream()
                .anyMatch(r -> r.getMember().getId() == memberId);
        if (!mine) {
            throw ApiException.forbidden("내 일행이 아닙니다.");
        }
        return settlements.findByParty(party).orElseThrow(() -> ApiException.notFound("정산이 없습니다."));
    }

    public record RouteView(List<GeoPoint> shared, List<GeoPoint> solo) {
    }

    @Transactional(readOnly = true)
    public RouteView routeView(long memberId, long partyId) {
        Party party = parties.findById(partyId).orElseThrow(() -> ApiException.notFound("없는 일행입니다."));
        List<RideRequest> riders = requests.findByPartyOrderByDropoffOrder(party);
        RideRequest mine = riders.stream().filter(r -> r.getMember().getId() == memberId).findFirst()
                .orElseThrow(() -> ApiException.forbidden("내 일행이 아닙니다."));
        GeoPoint origin = new GeoPoint(party.getHub().getLat(), party.getHub().getLon());
        List<GeoPoint> stops = riders.stream().map(r -> new GeoPoint(r.getDropoffLat(), r.getDropoffLon())).toList();
        try {
            return new RouteView(paths.coordinates(origin, stops),
                    paths.coordinates(origin, List.of(new GeoPoint(mine.getDropoffLat(), mine.getDropoffLon()))));
        } catch (RouteClient.RouteUnavailableException e) {
            return new RouteView(List.of(), List.of());
        }
    }

    private PartyView toView(long memberId, Party party) {
        List<RideRequest> riders = requests.findByPartyOrderByDropoffOrder(party);
        RideRequest mine = riders.stream()
                .filter(r -> r.getMember().getId() == memberId)
                .findFirst()
                .orElseThrow(() -> ApiException.forbidden("내 일행이 아닙니다."));
        boolean payer = party.getPayer().getId() == memberId;
        boolean beforeDeparture = clock.now().isBefore(party.getDepartureAt());
        boolean matched = mine.getStatus() == RequestStatus.MATCHED;
        boolean boarded = riders.stream().allMatch(r -> r.getStatus() == RequestStatus.COMPLETED);
        SettlementView settlement = settlements.findByParty(party).map(s -> toView(s, party, payer)).orElse(null);
        List<MemberView> members = riders.stream()
                .map(r -> new MemberView(r.getId(), r.getMember().getId(), r.getMember().getNickname(), r.getMemo(),
                        r.getDropoffOrder(), r.getDropoffName(), r.getExpectedShare(), r.isArrived(),
                        r.getSoloFare(), r.getSoloSeconds(), r.getSharedSeconds()))
                .toList();
        return new PartyView(party.getId(), party.getStatus(), party.getHub().getName(),
                party.getHub().getMeetingPoint(), party.getDepartureAt(),
                party.getExpectedTotal(), party.getPayer().getId(), party.getPayer().getNickname(), payer,
                matched && !mine.isArrived() && !clock.now().isAfter(party.getDepartureAt()), matched && beforeDeparture,
                payer && boarded && settlement == null, mine.getId(), members, settlement);
    }

    private SettlementView toView(Settlement settlement, Party party, boolean payer) {
        List<TransferRequest> list = transfers.findBySettlementOrderById(settlement);
        boolean warning = Math.abs(settlement.getPaidAmount() - party.getExpectedTotal())
                > party.getExpectedTotal() * WARNING_RATE;
        boolean canCorrect = payer && list.stream().allMatch(t -> t.getStatus() == TransferStatus.REQUESTED);
        List<TransferView> views = list.stream()
                .map(t -> new TransferView(t.getId(), t.getSender().getId(), t.getSender().getNickname(),
                        t.getAmount(), t.getStatus()))
                .toList();
        return new SettlementView(settlement.getPaidAmount(), settlement.getRevision(), warning, canCorrect,
                settlement.getReceiptPath() != null, views);
    }
}
