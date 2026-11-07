package com.campustaximate.server.service;

import com.campustaximate.server.domain.EndReason;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.PartyStatus;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.domain.Settlement;
import com.campustaximate.server.domain.TransferRequest;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.repository.SettlementRepository;
import com.campustaximate.server.repository.TransferRequestRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 이용 내역. 요청 상태만으로는 정산이 끝났는지 알 수 없어 일행과 정산까지 함께 본다. */
@Service
@RequiredArgsConstructor
public class RideHistoryService {

    public enum Stage { WAITING, CANCELED, UNMATCHED, NO_SHOW, MATCHED, AWAITING_AMOUNT, SETTLING, SETTLED, UNSETTLED,
        PARTY_CANCELED, LEFT_ALONE }

    /** paid는 내가 낸 돈, partyPaid는 실제 결제 금액이고, 정산 전에는 null이다. */
    public record Entry(RideRequest request, Stage stage, Long paid, Long partyPaid, Integer partySize) {
        Entry(RideRequest request, Stage stage) {
            this(request, stage, null, null, null);
        }
    }

    private final RideRequestRepository requests;
    private final SettlementRepository settlements;
    private final TransferRequestRepository transfers;

    @Transactional(readOnly = true)
    public List<Entry> of(long memberId) {
        return requests.findByMemberIdOrderByIdDesc(memberId).stream().map(r -> entry(r, memberId)).toList();
    }

    private Entry entry(RideRequest r, long memberId) {
        Party party = r.getParty();
        if (r.isNoShow() || r.getEndReason() == EndReason.NO_SHOW) {
            return new Entry(r, Stage.NO_SHOW);
        }
        if (r.getEndReason() == EndReason.PARTY_CANCELED) {
            return new Entry(r, Stage.PARTY_CANCELED);
        }
        if (r.getEndReason() == EndReason.LEFT_ALONE) {
            return new Entry(r, Stage.LEFT_ALONE);
        }
        if (party == null || r.getStatus() == RequestStatus.CANCELED || r.getStatus() == RequestStatus.UNMATCHED) {
            return new Entry(r, withoutParty(r.getStatus()));
        }
        Long partyPaid = settlements.findByParty(party).map(Settlement::getPaidAmount).orElse(null);
        return new Entry(r, stage(r, party), paid(party, memberId), partyPaid,
                requests.findByPartyOrderByRideOrder(party).size());
    }

    private static Stage withoutParty(RequestStatus status) {
        return switch (status) {
            case CANCELED -> Stage.CANCELED;
            case UNMATCHED -> Stage.UNMATCHED;
            default -> Stage.WAITING;
        };
    }

    private static Stage stage(RideRequest r, Party party) {
        PartyStatus status = party.getStatus();
        return switch (status) {
            case SETTLING -> Stage.SETTLING;
            case COMPLETED -> Stage.SETTLED;
            case UNSETTLED -> Stage.UNSETTLED;
            case CANCELED -> Stage.CANCELED;
            case CONFIRMED -> r.getStatus() == RequestStatus.COMPLETED ? Stage.AWAITING_AMOUNT : Stage.MATCHED;
        };
    }

    private Long paid(Party party, long memberId) {
        Settlement settlement = settlements.findByParty(party).orElse(null);
        if (settlement == null) {
            return null;
        }
        List<TransferRequest> list = transfers.findBySettlementOrderById(settlement);
        return list.stream().filter(t -> t.getSender().getId() == memberId).map(TransferRequest::getAmount).findFirst()
                .orElseGet(() -> settlement.getPaidAmount() - list.stream().mapToLong(TransferRequest::getAmount).sum());
    }
}
