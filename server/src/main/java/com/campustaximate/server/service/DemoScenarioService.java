package com.campustaximate.server.service;

import com.campustaximate.matching.Direction;
import com.campustaximate.server.common.*;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 상태를 직접 덮어쓰지 않고 시연 버튼을 기존 업무 서비스로 연결한다. */
@Service @Profile("demo") @RequiredArgsConstructor
public class DemoScenarioService {
    public enum Scenario { INSTANT, NO_SHOW, JOIN, FARE_OBJECTION, PAYER_NO_SHOW, MY_SUCCESSION, RIDE_UNCONFIRMED }
    public record Investigation(long memberId,String status,Instant deadlineAt) { }
    public record Progress(Instant now,Long partyId,String stage,String next,String role,String message,List<Investigation> investigations) { }
    private final DemoService demo;
    private final AppClock clock;
    private final SlotService slots;
    private final PartyService parties;
    private final RideRequestService rideRequests;
    private final RideRequestRepository requests;
    private final MemberRepository members;
    private final HubRepository hubs;
    private final PaymentService payments;
    private final PaymentOrderRepository orders;
    private final PartyReadinessService readiness;
    private final SettlementService settlements;
    private final RideConfirmationService confirmations;
    private final NoShowService noShows;
    private final PartyJoinService joins;
    private final PartyChatService chat;

    private PartyService.PartyView party(long actor) {
        return parties.openParties(actor).stream().findFirst().orElseGet(()->requests.findFirstByMemberIdAndPartyIsNotNullOrderByIdDesc(actor)
                .map(r->parties.view(actor,r.getParty().getId())).orElse(null));
    }
    public Progress status(long actor) { demo.requireEnabled(); return progress(actor,""); }
    private Progress progress(long actor,String message) {
        var waiting=requests.findByActiveMemberId(actor).filter(r->r.getStatus()==RequestStatus.WAITING);
        if(waiting.isPresent()) return new Progress(clock.now(),null,"일행 찾는 중","일행 확정",null,message,List.of());
        var p=party(actor);
        if(p==null) return new Progress(clock.now(),null,"요청 전","탑승 요청 만들기",null,message,List.of());
        var cases=noShows.list(actor,p.id()).stream().map(c->new Investigation(c.investigation().getMemberId(),c.investigation().getStatus(),c.investigation().getDeadlineAt())).toList();
        return new Progress(clock.now(),p.id(),label(p.status()),nextName(p),p.payer()?"내가 결제자":"나는 일행원 (선결제 대상)",message,cases);
    }
    private String label(PartyStatus status) {
        return switch(status) {
            case PREPAYING -> "선결제 대기"; case READY -> "출발 준비"; case PAYER_PENDING -> "결제자 승계 요청";
            case RIDE_UNCONFIRMED -> "운행 미확인"; case AWAITING_FARE -> "실제 금액 입력";
            case OBJECTION_WINDOW -> "요금 이의 기간"; case REVIEW_PENDING -> "관리자 검토";
            case SETTLED -> "정산 확정"; case COMPLETED -> "정산 완료"; default -> "운행 종료";
        };
    }
    private String nextName(PartyService.PartyView p) {
        return switch(p.status()) {
            case PREPAYING -> "선결제 기한";
            case READY -> clock.now().isBefore(p.departureAt().minusSeconds(600))?"택시 호출 시각":clock.now().isBefore(p.departureAt())?"출발 시각 T":"운행 확인 T+5분";
            case PAYER_PENDING -> "승계 수락 또는 거절";
            case RIDE_UNCONFIRMED -> "내 운행 답변";
            case AWAITING_FARE -> p.payer()?"내 실제 금액·영수증 입력":"샘플 결제자 금액 입력";
            case OBJECTION_WINDOW -> "24시간 뒤 노쇼 판정·정산 확정";
            case REVIEW_PENDING -> "관리자 계정에서 판정";
            case SETTLED -> "정산 처리 확인"; default -> "새 요청 만들기";
        };
    }
    private void moveTo(Instant target) {
        if(!target.isAfter(clock.now())) { demo.runNow(); return; }
        long minutes=(long)Math.ceil(Duration.between(clock.now(),target).toMillis()/60000.0);
        if(minutes>48*60) throw ApiException.conflict("DEMO_TIME_TOO_FAR","48시간 안의 다음 단계만 진행할 수 있어요.");
        demo.advance((int)Math.max(1,minutes));
    }
    private boolean sample(long id,long actor) {
        return id!=actor && members.findById(id).map(m->DataSeeder.isSeededDemo(m.getEmail())).orElse(false);
    }
    private void sampleOnly(PartyService.PartyView p,long actor) {
        if(p.members().stream().anyMatch(m->m.memberId()!=actor&&!sample(m.memberId(),actor)))
            throw ApiException.conflict("DEMO_REAL_MEMBER","시연 계정으로만 구성된 일행에서 실행해 주세요.");
    }
    private void simulatedOnly() {
        if(!payments.simulated()) throw ApiException.conflict("DEMO_PAYMENT_REQUIRED","자동 시나리오 결제는 모의 결제 모드에서만 가능해요. 토스 테스트 모드에서는 계정을 전환해 직접 결제해 주세요.");
    }
    private void approve(PartyService.PartyView p,long actor,boolean includeMe) {
        for(var o:orders.findByPartyIdOrderById(p.id())) {
            if(o.getPurpose()!=PaymentPurpose.PREPAY || o.getStatus()==PaymentStatus.APPROVED || o.getExcludedAt()!=null) continue;
            if(!includeMe&&!sample(o.getMemberId(),actor)) continue;
            simulatedOnly();
            if(!clock.now().isBefore(o.getDeadlineAt())) throw ApiException.conflict("DEMO_PAYMENT_LATE","선결제 기한이 지났어요. 새 요청으로 시연해 주세요.");
            var approved=payments.confirm(o.getMemberId(),o.getOrderId(),"demo_"+o.getOrderId(),o.getAmount());
            if(approved.status()!=PaymentStatus.APPROVED) throw ApiException.conflict("DEMO_PAYMENT_PENDING","샘플 결제가 확인 중이에요.");
            if(p.members().stream().anyMatch(m->m.memberId()==o.getMemberId())) chat.send(o.getMemberId(),p.id(),DemoTalk.paid(o.getMemberId()));
        }
    }
    private void arrive(PartyService.PartyView p,long actor,Long absent,boolean includeMe) {
        for(var m:p.members()) if(!m.arrived() && !Objects.equals(absent,m.memberId()) && (includeMe||sample(m.memberId(),actor))) {
            if(p.instant()) readiness.prepare(m.memberId(),p.id(),"ARRIVED"); else parties.arrive(m.memberId(),p.id());
            if(sample(m.memberId(),actor)) chat.send(m.memberId(),p.id(),DemoTalk.arrived(m.memberId(),m.memo()));
        }
    }
    public Progress next(long actor) {
        demo.requireEnabled();
        return slots.runLocked(()->{
            if(requests.findByActiveMemberId(actor).filter(r->r.getStatus()==RequestStatus.WAITING).isPresent()) {
                var result=demo.matchNow(actor); return progress(actor,result.message());
            }
            var p=party(actor);
            if(p==null) throw ApiException.conflict("DEMO_NO_PARTY","탑승 요청을 먼저 만들어 주세요.");
            sampleOnly(p,actor);
            switch(p.status()) {
                case PREPAYING -> {
                    approve(p,actor,false);
                    boolean unpaid=orders.findByPartyIdOrderById(p.id()).stream().anyMatch(o->o.getMemberId()==actor&&o.getPurpose()==PaymentPurpose.PREPAY&&o.getStatus()!=PaymentStatus.APPROVED&&o.getExcludedAt()==null);
                    if(unpaid) return progress(actor,"내 선결제를 먼저 마쳐 주세요. 시계는 이동하지 않았어요.");
                    moveTo(p.prepayDeadlineAt());
                }
                case READY -> {
                    arrive(p,actor,null,false);
                    if(clock.now().isBefore(p.departureAt().minusSeconds(600))) moveTo(p.departureAt().minusSeconds(600));
                    else if(clock.now().isBefore(p.departureAt())) {
                        var payer=parties.view(p.payerId(),p.id());
                        if(sample(p.payerId(),actor)&&payer.canCall()) readiness.call(p.payerId(),p.id());
                        moveTo(p.departureAt());
                    } else moveTo(p.departureAt().plusSeconds(300));
                }
                case AWAITING_FARE -> {
                    if(!sample(p.payerId(),actor)) return progress(actor,"내 실제 금액과 영수증을 일행 화면에서 입력해 주세요.");
                    settlements.submitForDemo(p.payerId(),p.id(),p.expectedTotal(),null,null);
                }
                case OBJECTION_WINDOW -> {
                    Instant target=p.settlement().objectionDeadlineAt();
                    for(var c:noShows.list(actor,p.id())) if(c.investigation().pending()&&c.investigation().getDeadlineAt().isAfter(target)) target=c.investigation().getDeadlineAt();
                    moveTo(target);
                }
                case SETTLED -> demo.runNow();
                default -> { return progress(actor,nextName(p)+"를 진행해 주세요."); }
            }
            return progress(actor,"다음 단계로 진행했어요.");
        });
    }
    /** 요청이 없을 때는 공동 승차 시연 경로로 새 요청을 만든다. 기존 요청은 즉시로 바꾸지 않는다. */
    private void ensureRequest(long actor,boolean instant) {
        var active=requests.findByActiveMemberId(actor);
        if(active.isPresent()) {
            if(instant&&active.get().getMode()!=RequestMode.INSTANT) throw ApiException.conflict("DEMO_INSTANT_REQUEST","예약 요청을 취소한 뒤 즉시 시나리오를 실행해 주세요.");
            return;
        }
        var me=members.findById(actor).orElseThrow(ApiException::unauthorized);
        var hub=hubs.findBySchool(me.getSchool().getId()).stream().filter(h->h.isActive()&&h.isFieldChecked()).findFirst().orElseThrow(()->ApiException.conflict("DEMO_NO_HUB","확인된 승차 지점이 필요해요."));
        var stop=DataSeeder.SAMPLE_DROPOFFS.get(1);
        var start=AppClock.nextSlotAfter(clock.now()).plusSeconds(3600);
        rideRequests.create(actor,new RideRequestService.NewRequest(Direction.FROM_HUB,hub.getId(),instant?null:start,instant?null:start.plusSeconds(3600),null,
                stop.name(),stop.lat(),stop.lon(),"시연 사용자",instant?RequestMode.INSTANT:RequestMode.RESERVATION,TimeBasis.DEPART,null,null,10));
    }
    private PartyService.PartyView prepare(long actor,DemoService.Role role,boolean instant) {
        var p=parties.openParties(actor).stream().filter(v->List.of(PartyStatus.PREPAYING,PartyStatus.READY).contains(v.status())).findFirst().orElse(null);
        if(p==null) {
            if(!parties.openParties(actor).isEmpty()) throw ApiException.conflict("DEMO_FINISH_PARTY","진행 중인 일행을 마친 뒤 새 시나리오를 시작해 주세요.");
            ensureRequest(actor,instant);
            var outcome=demo.matchNow(actor,role);
            if(outcome.status()!=RequestStatus.MATCHED) throw ApiException.conflict("DEMO_NOT_MATCHED",outcome.message());
            p=party(actor);
        }
        sampleOnly(p,actor);
        if(p.direction()!=Direction.FROM_HUB) throw ApiException.conflict("DEMO_COMMON_PICKUP_REQUIRED","공동 승차 일행에서 실행해 주세요.");
        if(instant&&!p.instant()) throw ApiException.conflict("DEMO_INSTANT_REQUEST","즉시 요청으로 실행해 주세요.");
        return p;
    }
    public Progress scenario(long actor,Scenario scenario,DemoService.Role role) {
        demo.requireEnabled();
        return slots.runLocked(()->{
            if(scenario!=Scenario.INSTANT) simulatedOnly();
            boolean succession=scenario==Scenario.PAYER_NO_SHOW||scenario==Scenario.MY_SUCCESSION;
            var p=prepare(actor,succession?DemoService.Role.PASSENGER:role,scenario==Scenario.INSTANT);
            if(scenario==Scenario.INSTANT) return progress(actor,"즉시 매칭됐어요. 선결제와 도착 표시 뒤 호출할 수 있어요.");
            if(scenario==Scenario.JOIN) {
                var joiner=members.findAll().stream().filter(m->sample(m.getId(),actor)&&m.getGender()==members.findById(actor).orElseThrow().getGender())
                        .filter(m->requests.findByActiveMemberId(m.getId()).isEmpty()&&!rideRequests.isRestricted(m.getId())).findFirst()
                        .orElseThrow(()->ApiException.conflict("DEMO_NO_SAMPLES","합류할 시연 계정이 없어요."));
                var mine=requests.findById(p.myRequestId()).orElseThrow();
                // 기존 중간 하차지와 같은 지점은 결제자 불변 조건을 유지한다. 제안 서비스가 경로·몫을 재검증한다.
                var stop=p.members().stream().filter(m->m.memberId()!=p.payerId()).findFirst().orElseThrow();
                var source=requests.findById(stop.requestId()).orElseThrow();
                var r=rideRequests.create(joiner.getId(),new RideRequestService.NewRequest(Direction.FROM_HUB,mine.getHub().getId(),mine.getWindowStart(),mine.getWindowEnd(),mine.getArriveBy(),source.getPointName(),source.getPointLat(),source.getPointLon(),"나중 합류",mine.getMode(),mine.getTimeBasis(),null,null,mine.getEarlyArrivalMinutes()));
                var join=joins.offer(joiner.getId(),p.id(),r.getId());
                var order=orders.findById(join.getPaymentOrderId()).orElseThrow();
                payments.confirm(joiner.getId(),order.getOrderId(),"demo_"+order.getOrderId(),order.getAmount());
                chat.send(joiner.getId(),p.id(),"합류했어요. 결제도 마쳤어요");
                return progress(actor,"나중 샘플이 합류를 제안하고 선결제를 마쳤어요.");
            }
            if(succession && !sample(p.payerId(),actor)) throw ApiException.conflict("DEMO_SAMPLE_PAYER","샘플 결제자가 필요해요. 일행원 역할로 새 일행을 만들어 주세요.");
            // payerId()는 long이라 그대로 두면 삼항식 전체가 long으로 풀려 null이 NPE가 된다. Long으로 맞춘다.
            Long absent=succession?Long.valueOf(p.payerId()):scenario==Scenario.NO_SHOW?p.members().stream().filter(m->sample(m.memberId(),actor)&&m.memberId()!=p.payerId()).map(PartyService.MemberView::memberId).findFirst()
                    .orElseThrow(()->ApiException.conflict("DEMO_NO_SAMPLES","결제자가 아닌 샘플이 필요해요.")):null;
            if(absent!=null && p.members().stream().filter(m->m.memberId()==absent).findFirst().orElseThrow().arrived()) throw ApiException.conflict("DEMO_ALREADY_ARRIVED","불참시킬 샘플이 이미 도착 표시했어요. 새 일행에서 실행해 주세요.");
            if(scenario==Scenario.RIDE_UNCONFIRMED && p.members().stream().allMatch(PartyService.MemberView::arrived))
                throw ApiException.conflict("DEMO_ALREADY_ARRIVED","모두 이미 도착 표시했어요. 새 일행에서 운행 미확인을 실행해 주세요.");
            approve(p,actor,true);
            if(scenario!=Scenario.RIDE_UNCONFIRMED) arrive(p,actor,absent,true);
            moveTo(p.prepayDeadlineAt());
            if(succession) {
                moveTo(p.departureAt());
                noShows.vote(actor,p.id(),p.payerId(),false);
                var current=parties.view(actor,p.id());
                if(scenario==Scenario.MY_SUCCESSION) {
                    if(!Objects.equals(current.successorId(),actor)) throw ApiException.conflict("DEMO_NOT_SUCCESSOR","현재 하차 순서에서 내가 승계자가 아니에요.");
                    noShows.succession(actor,p.id(),true);
                }
                return progress(actor,scenario==Scenario.MY_SUCCESSION?"샘플 결제자 노쇼 뒤 내가 결제자를 이어받았어요.":"샘플 결제자가 오지 않았어요. 일행 화면에서 승계를 수락하거나 거절해 보세요.");
            }
            moveTo(p.departureAt().plusSeconds(300));
            if(scenario==Scenario.RIDE_UNCONFIRMED) return progress(actor,"운행 시작 표시 없이 T+5분이 됐어요. 탔어요 / 못 탔어요로 답해 보세요.");
            var current=parties.view(actor,p.id());
            if(current.status()==PartyStatus.RIDE_UNCONFIRMED) confirmations.answer(p.payerId(),p.id(),true);
            current=parties.view(actor,p.id());
            if(current.settlement()==null) settlements.submitForDemo(p.payerId(),p.id(),p.expectedTotal(),null,null);
            if(scenario==Scenario.NO_SHOW) {
                for(var m:parties.view(actor,p.id()).members()) noShows.vote(m.memberId(),p.id(),absent,false);
                return progress(actor,"샘플 1명 탑승 미확인 → 일행의 안 왔어요 표시 완료. 다음 단계에서 24시간 뒤 판정해요.");
            }
            long objector=current.members().stream().filter(m->sample(m.memberId(),actor)&&m.memberId()!=p.payerId()).mapToLong(PartyService.MemberView::memberId).findFirst()
                    .orElseThrow(()->ApiException.conflict("DEMO_NO_SAMPLES","요금 이의를 낼 샘플이 필요해요."));
            settlements.objectToFare(objector,p.id(),"시연: 안내받은 금액과 달라 영수증 확인을 요청해요.");
            return progress(actor,"샘플이 요금 이의를 냈어요. 시연 관리자 계정으로 전환해 판정할 수 있어요.");
        });
    }
}
