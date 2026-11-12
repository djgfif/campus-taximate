package com.campustaximate.server.service;

import com.campustaximate.server.common.*;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 도착 표시는 탑승 증명이 아니다. 운행 여부가 불분명하면 환불부터 실행하지 않는다. */
@Service @RequiredArgsConstructor
public class RideConfirmationService {
    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final PartyMemberTermRepository terms;
    private final RideConfirmationRepository answers;
    private final PartyChangeService changes;
    private final PaymentService payments;
    private final NotificationService notifications;
    private final AppClock clock;
    private final NoShowService noShows;

    @Transactional(propagation=Propagation.MANDATORY)
    public void ask(Party party) {
        if(party.getRideAskedAt()!=null) return;
        changes.capture(party,requests.findByPartyOrderByRideOrder(party),true);
        party.setRideAskedAt(clock.now()); party.setStatus(PartyStatus.RIDE_UNCONFIRMED);
        for(var rider:requests.findByPartyOrderByRideOrder(party)) notifications.toMember(rider.getMember(),
                "[캠퍼스 택시메이트] 운행 확인", "일행과 택시에 탔나요? 앱에서 탔어요 또는 못 탔어요를 알려 주세요.");
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void markBoardingUnconfirmed(Party party) {
        var riders=requests.findByPartyOrderByRideOrder(party);
        changes.capture(party,riders,true);
        for(var rider:riders) if(!rider.isArrived()) terms.findByPartyIdAndRequestId(party.getId(),rider.getId())
                .filter(t->t.getBoardingUnconfirmedAt()==null).ifPresent(t->t.setBoardingUnconfirmedAt(clock.now()));
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void start(Party party,Instant started) {
        if(party.getStartedAt()!=null) return;
        markBoardingUnconfirmed(party);
        noShows.beginRide(party,started);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void notOperated(Party party) {
        noShows.notOperated(party);
        party.setStatus(PartyStatus.NOT_OPERATED);
        requests.findByPartyOrderByRideOrder(party).forEach(r->r.finish(RequestStatus.CANCELED));
        payments.refundAll(party.getId(),"운행 미성사 확인");
    }

    public boolean hasNegativeAnswer(long partyId) { return answers.findByPartyIdOrderById(partyId).stream().anyMatch(a->!a.isRode()); }

    @Transactional
    public void answer(long actor,long partyId,boolean rode) {
        Party party=parties.findByIdForUpdate(partyId).orElseThrow(()->ApiException.notFound("없는 일행입니다."));
        terms.findByPartyIdAndMemberId(partyId,actor).filter(t->t.getExitedAt()==null || "BOARDING_UNCONFIRMED".equals(t.getExitReason()))
                .orElseThrow(()->ApiException.forbidden("현재 일행원만 운행을 확인할 수 있어요."));
        if(!party.paymentFlow() || party.getRideAskedAt()==null) throw ApiException.conflict("RIDE_NOT_ASKED", "아직 운행 확인 대상이 아니에요.");
        var old=answers.findByPartyIdAndMemberId(partyId,actor).orElse(null);
        if(old!=null) {
            if(old.isRode()!=rode) throw ApiException.conflict("ANSWER_FINAL", "첫 운행 답변은 바꿀 수 없어요.");
            return;
        }
        if(party.getStatus()==PartyStatus.RIDE_UNCONFIRMED && !clock.now().isBefore(party.getDepartureAt().plusSeconds(24*3600))) notOperated(party);
        RideConfirmation answer=new RideConfirmation(); answer.setPartyId(partyId); answer.setMemberId(actor);
        answer.setRode(rode); answer.setAnsweredAt(clock.now()); answers.saveAndFlush(answer);
        var all=answers.findByPartyIdOrderById(partyId);
        var payer=all.stream().filter(a->a.getMemberId().equals(party.getPayer().getId())).findFirst().orElse(null);
        boolean conflict=payer!=null && (all.stream().anyMatch(a->!a.getMemberId().equals(payer.getMemberId())&&a.isRode()!=payer.isRode()) || !payer.isRode()&&party.getStartedAt()!=null);
        if(party.getStatus()==PartyStatus.NOT_OPERATED) {
            if(rode) {
                party.setAdminReviewRequired(true);
                if(payments.pauseUnstartedRefunds(partyId)) { party.setReviewReason("RIDE_DISPUTE"); party.setStatus(PartyStatus.REVIEW_PENDING); }
                else party.setReviewReason("LATE_RIDE_ANSWER_AFTER_REFUND");
            }
            return;
        }
        if(conflict) {
            party.setAdminReviewRequired(true); party.setReviewReason("RIDE_DISPUTE");
            if(List.of(PartyStatus.SETTLED,PartyStatus.COMPLETED,PartyStatus.FARE_MISSING).contains(party.getStatus())) return;
            party.setStatus(PartyStatus.REVIEW_PENDING); return;
        }
        if(payer==null || party.getStatus()==PartyStatus.REVIEW_PENDING || party.getStatus()!=PartyStatus.RIDE_UNCONFIRMED) return;
        if(payer.isRode()) start(party,party.getDepartureAt());
        else notOperated(party);
    }
}
