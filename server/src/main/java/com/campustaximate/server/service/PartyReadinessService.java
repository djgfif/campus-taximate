package com.campustaximate.server.service;

import com.campustaximate.server.common.*;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 호출은 외부 배차가 아니라 결제자가 수동 호출을 시작한다는 표시다. 운행 시작과 구분한다. */
@Service @RequiredArgsConstructor
public class PartyReadinessService {
    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final PaymentOrderRepository orders;
    private final AppClock clock;
    private final PaymentLifecycleService lifecycle;
    private final RideConfirmationService confirmations;
    private final PartyJoinService joins;
    private final PlatformTransactionManager manager;
    private final PartyChatService chatLog;
    public record State(boolean callReady,boolean canCall,List<String> notReadyReasons) { }

    public State state(Party party,long actor,List<RideRequest> riders) {
        boolean phase=List.of(PartyStatus.PREPAYING,PartyStatus.READY).contains(party.getStatus());
        var reasons=new ArrayList<String>();
        boolean paid=true;
        for(var rider:riders) {
            if(rider.getMember().getId().equals(party.getPayer().getId())) continue;
            var order=orders.findByPartyIdAndMemberIdAndPurpose(party.getId(),rider.getMember().getId(),PaymentPurpose.PREPAY).orElse(null);
            if(party.paymentFlow() && (order==null || order.getStatus()!=PaymentStatus.APPROVED || order.getExcludedAt()!=null)) paid=false;
        }
        if(!paid) reasons.add("선결제 승인 또는 미납 제외 처리를 기다리고 있어요.");
        boolean close=riders.stream().allMatch(r->r.isArrived()||r.getNearbyAt()!=null);
        if(party.isInstant()&&!close) reasons.add("아직 도착 또는 2분 안 도착 표시를 하지 않은 일행이 있어요.");
        boolean time= !clock.now().isBefore(party.isInstant()?party.getDepartureAt():party.getDepartureAt().minusSeconds(600));
        if(!party.isInstant()&&!time) reasons.add("예약 일행은 출발 10분 전부터 호출할 수 있어요.");
        boolean pending=party.getPendingJoinId()!=null && joins.activeReservation(party.getPendingJoinId());
        if(pending)reasons.add("합류 선결제를 기다리고 있어요.");
        boolean ready=phase && paid && !pending && (party.isInstant()?close:time);
        boolean allowed=phase && (party.isInstant()?ready||time:ready) && party.getCalledAt()==null && party.getPayer().getId()==actor;
        return new State(ready,allowed,List.copyOf(reasons));
    }

    @Transactional
    public void prepare(long actor,long id,String state) {
        Party party=parties.findByIdForUpdate(id).orElseThrow(()->ApiException.notFound("없는 일행입니다."));
        if(!party.isInstant() || !List.of(PartyStatus.PREPAYING,PartyStatus.READY).contains(party.getStatus())) throw ApiException.conflict("NOT_PREPARING","준비 상태를 표시할 수 없는 일행입니다.");
        RideRequest mine=requests.findByPartyOrderByRideOrder(party).stream().filter(r->r.getMember().getId()==actor).findFirst()
                .orElseThrow(()->ApiException.forbidden("현재 일행원만 표시할 수 있어요."));
        if(mine.getStatus()!=RequestStatus.MATCHED) throw ApiException.conflict("NOT_PREPARING","이미 운행을 시작했어요.");
        if("ARRIVED".equals(state)) {mine.setArrived(true);mine.setNearbyAt(null);chatLog.system(party,actor,"%s님이 승차 지점에 도착했어요.");}
        else if("NEARBY".equals(state)) {if(!mine.isArrived()){mine.setNearbyAt(clock.now());chatLog.system(party,actor,"%s님이 2분 안에 도착해요.");}}
        else throw ApiException.badRequest("INVALID_READINESS","도착 또는 2분 안 도착 중 골라 주세요.");
    }

    public void call(long actor,long id) {
        // 기한 회차가 늦어져도 호출 전에 처리 중 선결제의 조회·제외를 먼저 끝낸다.
        new com.campustaximate.server.common.RetryingTransactionTemplate(manager).executeWithoutResult(tx->{
            var before=parties.findById(id).orElseThrow(()->ApiException.notFound("없는 일행입니다."));
            if(before.getPayer().getId()!=actor) throw ApiException.forbidden("결제자만 호출을 표시할 수 있어요.");
        });
        joins.expireDue();
        lifecycle.beforeDepartures();
        new com.campustaximate.server.common.RetryingTransactionTemplate(manager).executeWithoutResult(tx->{
            Party party=parties.findByIdForUpdate(id).orElseThrow();
            if(party.getPayer().getId()!=actor) throw ApiException.forbidden("결제자가 바뀌었어요. 일행을 다시 확인해 주세요.");
            if(party.getCalledAt()!=null) return;
            var riders=requests.findByPartyOrderByRideOrder(party);
            if(!state(party,actor,riders).canCall()) throw ApiException.conflict("CALL_NOT_READY","아직 호출 준비가 되지 않았어요.");
            party.setCalledAt(clock.now());
            if(party.isInstant()&&!clock.now().isBefore(party.getDepartureAt())) confirmations.markBoardingUnconfirmed(party);
        });
    }
}
