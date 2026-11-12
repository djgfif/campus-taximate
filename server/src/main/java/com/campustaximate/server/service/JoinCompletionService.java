package com.campustaximate.server.service;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.*;import com.campustaximate.server.repository.*;
import java.util.*;import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** PG 승인 적용과 같은 일행→결제 행 잠금 트랜잭션에서 합류를 확정한다. PG를 다시 호출하지 않는다. */
@Service @RequiredArgsConstructor
public class JoinCompletionService {
    private final PartyJoinRepository joins;private final PartyJoinMemberRepository members;
    private final RideRequestRepository requests;private final PartyChangeService changes;private final MemberSafetyService safety;
    private final NotificationService notifications;private final AppClock clock;
    public boolean pending(PaymentOrder order,Party party){
        return joins.findByPaymentOrderId(order.getId()).filter(j->"PENDING".equals(j.getState())&&Objects.equals(party.getPendingJoinId(),j.getId())
                &&Objects.equals(party.getPayer().getId(),j.getPayerId())&&party.getGroupVersion()==j.getBaseGroupVersion()&&List.of(PartyStatus.PREPAYING,PartyStatus.READY).contains(party.getStatus())
                &&party.getStartedAt()==null&&party.getCalledAt()==null&&clock.now().isBefore(j.getDeadlineAt())).isPresent();
    }
    /** true면 만료된 합류의 입금이므로 전액 환불한다. 일반 선결제에는 개입하지 않는다. */
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean approved(PaymentOrder order,Party party){
        var join=joins.findByPaymentOrderId(order.getId()).orElse(null);if(join==null)return false;
        if("CONFIRMED".equals(join.getState()))return false;
        if(!pending(order,party))return reject(join,party);
        var newcomer=requests.findById(join.getRequestId()).orElseThrow();
        var current=requests.findByPartyOrderByRideOrder(party);var plan=members.findByJoinIdOrderByRideOrder(join.getId());
        if(newcomer.getStatus()!=RequestStatus.JOINING||!Objects.equals(newcomer.getPendingJoinId(),join.getId())||current.size()+1!=plan.size())return reject(join,party);
        var ids=current.stream().map(RideRequest::getId).collect(java.util.stream.Collectors.toSet());ids.add(newcomer.getId());
        if(!ids.equals(plan.stream().map(PartyJoinMember::getRequestId).collect(java.util.stream.Collectors.toSet())))return reject(join,party);
        Map<Long,Set<Long>> avoid=safety.avoidMap(java.util.stream.Stream.concat(current.stream(),java.util.stream.Stream.of(newcomer)).map(r->r.getMember().getId()).toList());
        for(var a:current)if(avoid.getOrDefault(a.getMember().getId(),Set.of()).contains(newcomer.getMember().getId()))return reject(join,party);
        for(var row:plan){
            RideRequest rider=row.getRequestId().equals(newcomer.getId())?newcomer:current.stream().filter(r->r.getId().equals(row.getRequestId())).findFirst().orElseThrow();
            if(rider!=newcomer&&row.getExpectedShare()>rider.getExpectedShare())return reject(join,party);
        }
        for(var row:plan){
            RideRequest rider=row.getRequestId().equals(newcomer.getId())?newcomer:current.stream().filter(r->r.getId().equals(row.getRequestId())).findFirst().orElseThrow();
            rider.setParty(party);rider.setHub(party.getHub());rider.setRideOrder(row.getRideOrder());rider.setSharedSeconds(row.getRideSeconds());
            rider.setRatioUnits(row.getRatioUnits());rider.setExpectedShare(row.getExpectedShare());rider.setStatus(RequestStatus.MATCHED);
        }
        newcomer.setPendingJoinId(null);party.setPendingJoinId(null);party.setExpectedTotal(join.getTotalFare());join.setState("CONFIRMED");
        changes.capture(party,List.of(newcomer),false);safety.remember(party,List.of(newcomer));changes.changed(party,Set.of(),"JOIN_CONFIRMED",Set.of());
        for(var rider:requests.findByPartyOrderByRideOrder(party))notifications.toMember(rider.getMember(),"[캠퍼스 택시메이트] 합류 확정","일행이 합류했어요. 현재 예상 분담금은 "+rider.getExpectedShare()+"원이에요. 선결제 차액은 정산 때 돌려드려요.");
        return false;
    }
    private boolean reject(PartyJoin join,Party party){
        if("PENDING".equals(join.getState()))join.setState("CANCELED");if(Objects.equals(party.getPendingJoinId(),join.getId()))party.setPendingJoinId(null);
        requests.findById(join.getRequestId()).filter(r->r.getStatus()==RequestStatus.JOINING&&Objects.equals(r.getPendingJoinId(),join.getId()))
            .ifPresent(r->{r.setPendingJoinId(null);r.finish(RequestStatus.CANCELED);});
        return true;
    }
}
