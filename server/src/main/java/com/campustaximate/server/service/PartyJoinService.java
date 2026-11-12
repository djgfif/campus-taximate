package com.campustaximate.server.service;
import com.campustaximate.matching.*;
import com.campustaximate.server.common.*;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import com.campustaximate.server.route.RouteClient;
import com.campustaximate.settlement.SettlementCalculator;
import java.time.*;import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/** 합류 경로는 잠금 밖에서 확인하고, 승인 전에는 좌석만 예약한다. */
@Service @RequiredArgsConstructor
public class PartyJoinService {
    private final PartyRepository parties;private final RideRequestRepository requests;
    private final PartyJoinRepository joins;private final PartyJoinMemberRepository joinMembers;private final PaymentOrderRepository orders;
    private final RoadLegStore legs;private final RouteClient routes;private final RoutePathStore paths;
    private final PaymentService payments;private final MemberSafetyService safety;private final AppClock clock;
    private final MatchingSettings settings;private final PlatformTransactionManager manager;
    @Value("${app.matching.join-payment-minutes:5}")private int paymentMinutes;
    private RetryingTransactionTemplate tx(){return new RetryingTransactionTemplate(manager);}
    private record Plan(long partyId,long groupVersion,long payerId,long requestId,long requestVersion,long memberId,Instant departure,
                        List<MatchRequest> input,Map<Long,Long> expected,Map<Long,Long> fares,Map<Long,GeoPoint> origins){}
    private Instant cutoff(Party p){return p.isInstant()?p.getConfirmedAt().plusSeconds(settings.instantPrepMinutes()*60L):p.getDepartureAt().minusSeconds(settings.leadMinutes()*60L);}
    private void open(Party p){
        if(!p.paymentFlow()||p.getDirection()!=Direction.FROM_HUB||p.getHub()==null||!p.getHub().isActive()||!p.getHub().isFieldChecked()||p.getCalledAt()!=null||!List.of(PartyStatus.PREPAYING,PartyStatus.READY).contains(p.getStatus())
                ||p.getStartedAt()!=null||!clock.now().isBefore(cutoff(p)))throw ApiException.conflict("JOIN_CLOSED","합류 마감이 지났거나 합류할 수 없는 일행이에요.");
    }
    private Plan readPlan(long actor,long partyId,long requestId){
        Party p=parties.findById(partyId).orElseThrow(()->ApiException.notFound("없는 일행입니다."));open(p);
        if(p.getPendingJoinId()!=null)throw ApiException.conflict("JOIN_RESERVED","다른 사람의 합류 결제를 기다리고 있어요.");
        RideRequest r=requests.findById(requestId).orElseThrow(()->ApiException.notFound("없는 요청입니다."));
        if(r.getMember().getId()!=actor)throw ApiException.forbidden("본인 요청만 합류할 수 있어요.");
        if(r.getStatus()!=RequestStatus.WAITING)throw ApiException.conflict("JOIN_REQUEST_CHANGED","대기 중인 요청만 합류할 수 있어요.");
        var current=requests.findByPartyOrderByRideOrder(p);if(current.size()>=settings.maxGroupSize())throw ApiException.conflict("JOIN_FULL","일행이 가득 찼어요.");
        if(orders.findByPartyIdAndMemberIdAndPurpose(partyId,actor,PaymentPurpose.PREPAY).isPresent())throw ApiException.conflict("JOIN_ALREADY_ATTEMPTED","이 일행의 합류 주문은 이미 처리되었어요.");
        List<MatchRequest> input=new ArrayList<>();Map<Long,Long> expected=new HashMap<>(),fares=new HashMap<>();Map<Long,GeoPoint> origins=new HashMap<>();
        for(var rider:current){
            input.add(SlotService.toEngine(rider).stream().filter(m->m.anchorId()==p.getHub().getId()).findFirst().orElseThrow());
            expected.put(rider.getId(),rider.getExpectedShare());fares.put(rider.getId(),rider.getSoloFare());origins.put(rider.getId(),rider.origin());
        }
        var poolKey=input.getFirst().poolKey();
        if(input.stream().anyMatch(m->!m.poolKey().equals(poolKey)))throw ApiException.conflict("JOIN_POOL","기존 일행의 매칭 조건이 바뀌었어요.");
        MatchRequest newcomer=SlotService.toEngine(r).stream().filter(m->m.poolKey().equals(poolKey)).findFirst()
            .orElseThrow(()->ApiException.conflict("JOIN_POOL","승차 지점·방향·성별·시간 기준이 맞지 않아요."));
        // 방금 만든 요청도 합류할 수 있게 분 단위로 자르지 않은 현재 시각으로 본다(분으로 자르면 요청 시각보다 앞선다).
        if(!TripPlan.of(newcomer.direction(),settings).eligible(newcomer,clock.now()))throw ApiException.conflict("JOIN_TIME","요청 모집 마감이 지났어요.");
        if(clock.now().plusSeconds(newcomer.walkSeconds()).isAfter(p.getDepartureAt()))throw ApiException.conflict("JOIN_TIME","기존 출발 시각까지 승차 지점에 도착할 수 없어요.");
        input.add(newcomer);fares.put(r.getId(),r.getSoloFare());origins.put(r.getId(),r.origin());
        var avoids=safety.avoidMap(input.stream().map(MatchRequest::memberId).toList());
        input=input.stream().map(m->m.withAvoidMemberIds(avoids.getOrDefault(m.memberId(),Set.of()))).toList();
        for(var a:input)for(var b:input)if(a.id()!=b.id()&&(a.memberId()==b.memberId()||a.avoids(b)))throw ApiException.conflict("JOIN_BLOCKED","함께 매칭할 수 없는 일행이에요.");
        return new Plan(partyId,p.getGroupVersion(),p.getPayer().getId(),requestId,r.getVersion(),actor,p.getDepartureAt(),input,expected,fares,origins);
    }
    public PartyJoin offer(long actor,long partyId,long requestId){
        Plan p=tx().execute(s->readPlan(actor,partyId,requestId));
        legs.prepare(p.input(),clock.now());
        Candidate estimated=new EstimateCalculator(settings).evaluate(p.input(),legs.provider()).orElseThrow(()->ApiException.conflict("JOIN_DELAY","합류 경로가 없거나 동승 지연 기준을 넘어요."));
        if(estimated.order().getLast().memberId()!=p.payerId())throw ApiException.conflict("JOIN_PAYER_CHANGED","결제자가 바뀌는 합류는 할 수 없어요.");
        Candidate candidate=new Candidate(estimated.order(),estimated.totalKm(),p.departure(),estimated.rides());
        var verifier=new RouteGroupVerifier(routes,new HashMap<>(),clock.now(),paths,p.fares(),p.origins(),AppClock.minuteOf(clock.now()),settings).fixedDeparture(p.departure());
        Verification checked=verifier.check(candidate);
        if(checked.verdict()!=Verdict.PASS||!p.departure().equals(checked.departure()))throw ApiException.conflict("JOIN_VERIFICATION","기존 출발 시각의 경로를 확인하지 못했거나 합류 조건을 충족하지 않아요.");
        var verified=verifier.routesOf(candidate.order());var units=SettlementCalculator.ratioUnits(verified.solo().stream().map(RouteClient.Route::fare).toList());
        var values=SettlementCalculator.shares(verified.total(),units,candidate.size()-1);
        for(int i=0;i<candidate.size();i++)if(p.expected().containsKey(candidate.order().get(i).id())&&values.get(i)>p.expected().get(candidate.order().get(i).id()))throw ApiException.conflict("JOIN_SHARE_INCREASE","기존 일행의 예상 분담금이 오르는 합류는 할 수 없어요.");
        return tx().execute(s->{
            safety.lockMembers(p.input().stream().map(MatchRequest::memberId).toList());
            Party party=parties.findByIdForUpdate(partyId).orElseThrow();open(party);
            int walk=p.input().stream().filter(m->m.id()==requestId).findFirst().orElseThrow().walkSeconds();
            if(clock.now().plusSeconds(walk).isAfter(party.getDepartureAt()))throw ApiException.conflict("JOIN_TIME","기존 출발 시각까지 승차 지점에 도착할 수 없어요.");
            if(party.getPendingJoinId()!=null||party.getGroupVersion()!=p.groupVersion()||party.getPayer().getId()!=p.payerId())throw ApiException.conflict("JOIN_CHANGED","일행이나 남은 자리가 바뀌었어요.");
            var newcomer=requests.findById(requestId).orElseThrow();var current=requests.findByPartyOrderByRideOrder(party);
            if(newcomer.getStatus()!=RequestStatus.WAITING||newcomer.getVersion()!=p.requestVersion()||current.size()>=settings.maxGroupSize()
                ||!current.stream().map(RideRequest::getId).collect(java.util.stream.Collectors.toSet()).equals(p.expected().keySet()))throw ApiException.conflict("JOIN_CHANGED","요청이나 일행이 바뀌었어요.");
            var currentAvoids=safety.avoidMap(p.input().stream().map(MatchRequest::memberId).toList());
            for(var old:current)if(currentAvoids.getOrDefault(old.getMember().getId(),Set.of()).contains(actor))throw ApiException.conflict("JOIN_BLOCKED","함께 매칭할 수 없는 일행이에요.");
            PartyJoin join=new PartyJoin();join.setPartyId(partyId);join.setRequestId(requestId);join.setMemberId(actor);join.setState("PENDING");
            join.setOfferedAt(clock.now());Instant paymentDeadline=clock.now().plusSeconds(paymentMinutes*60L);join.setDeadlineAt(paymentDeadline.isAfter(party.getDepartureAt())?party.getDepartureAt():paymentDeadline);join.setBaseGroupVersion(p.groupVersion());join.setPayerId(p.payerId());join.setDepartureAt(p.departure());join.setTotalFare(verified.total());joins.save(join);
            int newIndex=-1;
            for(int i=0;i<candidate.size();i++){
                MatchRequest request=candidate.order().get(i);PartyJoinMember row=new PartyJoinMember();row.setJoinId(join.getId());row.setRequestId(request.id());row.setRideOrder(i+1);
                row.setRideSeconds(verified.rideSeconds().get(i));row.setRatioUnits(units.get(i));row.setExpectedShare(values.get(i));joinMembers.save(row);if(request.id()==requestId)newIndex=i;
            }
            var order=payments.createObligation(partyId,actor,requestId,null,PaymentPurpose.PREPAY,PaymentService.prepayAmount(values.get(newIndex)),join.getDeadlineAt());
            join.setPaymentOrderId(order.getId());party.setPendingJoinId(join.getId());newcomer.setPendingJoinId(join.getId());newcomer.setStatus(RequestStatus.JOINING);
            return join;
        });
    }
    public void offerWaiting(Instant slot,List<RideRequest> waiting){
        for(var request:waiting){if(request.getRequestedAt().isAfter(slot))continue;
            for(var party:parties.findByStatusIn(List.of(PartyStatus.PREPAYING,PartyStatus.READY))){
                try{offer(request.getMember().getId(),party.getId(),request.getId());break;}
                catch(ApiException denied){MatchingDiagnostics.reason(slot,List.of(request.getId()),denied.getCode(),party.getId());}
            }
        }
    }
    public void expireDue(){
        for(var reference:joins.findByStateOrderById("PENDING"))tx().executeWithoutResult(s->{
            Party party=parties.findByIdForUpdate(reference.getPartyId()).orElseThrow();var join=joins.findById(reference.getId()).orElseThrow();
            if(!"PENDING".equals(join.getState()))return;
            if(clock.now().isBefore(join.getDeadlineAt())&&party.getGroupVersion()==join.getBaseGroupVersion()&&List.of(PartyStatus.PREPAYING,PartyStatus.READY).contains(party.getStatus()))return;
            join.setState("EXPIRED");if(Objects.equals(party.getPendingJoinId(),join.getId()))party.setPendingJoinId(null);
            var request=requests.findById(join.getRequestId()).orElseThrow();if(request.getStatus()==RequestStatus.JOINING&&Objects.equals(request.getPendingJoinId(),join.getId())){request.setPendingJoinId(null);request.finish(RequestStatus.CANCELED);}
            var order=orders.lockById(join.getPaymentOrderId()).orElseThrow();order.setExcludedAt(clock.now());if(order.getStatus()!=PaymentStatus.APPROVED)order.setStatus(PaymentStatus.EXPIRED);
            payments.refundTo(order.getId(),order.getAmount(),"합류 기한 종료 전액 환불");
        });
    }
    public boolean cancelPending(long actor,long requestId){
        var ref=requests.findById(requestId).orElse(null);if(ref==null||ref.getStatus()!=RequestStatus.JOINING)return false;
        if(ref.getMember().getId()!=actor)throw ApiException.notFound("없는 요청입니다.");
        var reference=joins.findById(ref.getPendingJoinId()).orElseThrow();
        return tx().execute(s->{
            Party party=parties.findByIdForUpdate(reference.getPartyId()).orElseThrow();var join=joins.findById(reference.getId()).orElseThrow();
            if(!"PENDING".equals(join.getState()))return false;
            join.setState("CANCELED");if(Objects.equals(party.getPendingJoinId(),join.getId()))party.setPendingJoinId(null);
            var request=requests.findById(requestId).orElseThrow();request.setPendingJoinId(null);request.finish(RequestStatus.CANCELED);
            var order=orders.lockById(join.getPaymentOrderId()).orElseThrow();order.setExcludedAt(clock.now());if(order.getStatus()!=PaymentStatus.APPROVED)order.setStatus(PaymentStatus.EXPIRED);
            payments.refundTo(order.getId(),order.getAmount(),"합류 취소 전액 환불");return true;
        });
    }
    public boolean activeReservation(long joinId){return joins.findById(joinId).filter(j->"PENDING".equals(j.getState())&&clock.now().isBefore(j.getDeadlineAt())).isPresent();}
    public List<PartyJoin> mine(long actor){return joins.findByMemberIdOrderByIdDesc(actor);}
}
