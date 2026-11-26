package com.campustaximate.server.service;

import com.campustaximate.server.domain.EndReason;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.PartyStatus;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.domain.Settlement;
import com.campustaximate.server.domain.SettlementShare;
import com.campustaximate.server.domain.TransferRequest;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.repository.SettlementRepository;
import com.campustaximate.server.repository.SettlementShareRepository;
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
        PARTY_CANCELED, LEFT_ALONE, PREPAYING, READY, OBJECTION_WINDOW, REVIEW_PENDING, NOT_OPERATED, FARE_MISSING, DISSOLVED, RIDE_UNCONFIRMED,
        BOARDING_UNCONFIRMED, BOARDING_REVIEW, BOARDING_NO_SHOW, BOARDING_EXONERATED }

    /** paid는 기존 계약 금액(결제자는 현장 결제 합계), myShare는 정산 확정된 본인 분담금, payer는 결제자 여부다. 정산 전에는 null이다. */
    public record Entry(RideRequest request, Stage stage, Long paid, Long partyPaid, Integer partySize,
                        Long myShare, Boolean payer) {
        Entry(RideRequest request, Stage stage) {
            this(request, stage, null, null, null, null, null);
        }
    }

    private final RideRequestRepository requests;
    private final SettlementRepository settlements;
    private final SettlementShareRepository shares;
    private final TransferRequestRepository transfers;
    private final com.campustaximate.server.repository.PaymentOrderRepository paymentOrders;
    private final com.campustaximate.server.repository.BoardingCaseRepository boardingCases;

    @Transactional(readOnly = true)
    public List<Entry> of(long memberId) {
        return requests.findTop50ByMemberIdOrderByIdDesc(memberId).stream().map(r -> entry(r, memberId)).toList();
    }

    private Entry entry(RideRequest r, long memberId) {
        Party party = r.getParty();
        if (r.getEndReason() == EndReason.BOARDING_UNCONFIRMED) {
            Stage boardingStage = boardingCases.findByRequestIdAndMemberId(r.getId(), memberId)
                    .map(c -> switch(c.getStatus()) {
                        case "REVIEW" -> Stage.BOARDING_REVIEW;
                        case "CONFIRMED" -> Stage.BOARDING_NO_SHOW;
                        case "EXONERATED" -> Stage.BOARDING_EXONERATED;
                        default -> Stage.BOARDING_UNCONFIRMED;
                    }).orElse(Stage.BOARDING_UNCONFIRMED);
            return new Entry(r, boardingStage);
        }
        if (r.isNoShow() || r.getEndReason() == EndReason.NO_SHOW) {
            return new Entry(r, Stage.NO_SHOW);
        }
        if (r.getEndReason() == EndReason.PARTY_CANCELED) {
            return new Entry(r, Stage.PARTY_CANCELED);
        }
        if (r.getEndReason() == EndReason.LEFT_ALONE) {
            return new Entry(r, Stage.LEFT_ALONE);
        }
        if (party != null && List.of(PartyStatus.NOT_OPERATED, PartyStatus.RIDE_UNCONFIRMED, PartyStatus.REVIEW_PENDING).contains(party.getStatus())) {
            return new Entry(r, stage(r, party), null, null, null, null, isPayer(party, memberId));
        }
        if (party == null || r.getStatus() == RequestStatus.CANCELED || r.getStatus() == RequestStatus.UNMATCHED) {
            return new Entry(r, withoutParty(r.getStatus()));
        }
        Settlement settlement = settlements.findByParty(party).orElse(null);
        Long partyPaid = settlement == null ? null : settlement.getPaidAmount();
        Long paid = paid(party, settlement, memberId);
        return new Entry(r, stage(r, party), paid, partyPaid,
                requests.findByPartyOrderByRideOrder(party).size(), shareOf(settlement, memberId, paid), isPayer(party, memberId));
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
            case PREPAYING -> Stage.PREPAYING;
            case READY -> Stage.READY;
            case PAYER_PENDING -> Stage.REVIEW_PENDING;
            case RIDE_UNCONFIRMED -> Stage.RIDE_UNCONFIRMED;
            case AWAITING_FARE -> Stage.AWAITING_AMOUNT;
            case OBJECTION_WINDOW -> Stage.OBJECTION_WINDOW;
            case REVIEW_PENDING -> Stage.REVIEW_PENDING;
            case SETTLED -> Stage.SETTLING;
            case NOT_OPERATED -> Stage.NOT_OPERATED;
            case FARE_MISSING -> Stage.FARE_MISSING;
            case DISSOLVED -> Stage.DISSOLVED;
            case SETTLING -> Stage.SETTLING;
            case COMPLETED -> Stage.SETTLED;
            case UNSETTLED -> Stage.UNSETTLED;
            case CANCELED -> Stage.CANCELED;
            case CONFIRMED -> r.getStatus() == RequestStatus.COMPLETED ? Stage.AWAITING_AMOUNT : Stage.MATCHED;
        };
    }

    private static Boolean isPayer(Party party, long memberId) {
        return party.getPayer() != null && Long.valueOf(memberId).equals(party.getPayer().getId());
    }

    private Long shareOf(Settlement settlement, long memberId, Long fallbackPaid) {
        if (settlement == null) {
            return null;
        }
        return shares.findBySettlementIdOrderByRideOrder(settlement.getId()).stream()
                .filter(s -> Long.valueOf(memberId).equals(s.getMemberId()))
                .map(SettlementShare::getAmount)
                .findFirst()
                .orElse(fallbackPaid);
    }

    private Long paid(Party party, Settlement settlement, long memberId) {
        if (settlement == null) {
            return null;
        }
        if (party.paymentFlow()) {
            long net = paymentOrders.findByPartyIdOrderById(party.getId()).stream().filter(o -> o.getMemberId() == memberId)
                    .mapToLong(o -> o.getApprovedAmount() - o.getRefundedAmount()).sum();
            // 모의 지급은 실제 송금이 아니므로 현금 지출에서 차감하지 않는다.
            return party.getPayer().getId() == memberId ? settlement.getPaidAmount() + net : net;
        }
        List<TransferRequest> list = transfers.findBySettlementOrderById(settlement);
        return list.stream().filter(t -> t.getSender().getId() == memberId).map(TransferRequest::getAmount).findFirst()
                .orElseGet(() -> settlement.getPaidAmount() - list.stream().mapToLong(TransferRequest::getAmount).sum());
    }
}
