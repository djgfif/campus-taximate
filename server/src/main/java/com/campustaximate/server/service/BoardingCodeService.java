package com.campustaximate.server.service;

import com.campustaximate.server.common.*;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Team-member proposal: arrival is separate from actual boarding and explicit departure. */
@Service @RequiredArgsConstructor
public class BoardingCodeService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final BoardingAttemptRepository attempts;
    private final RideConfirmationService rides;
    private final PartyChatService chat;
    private final AppClock clock;
    public record State(int generation, String code, Instant deadlineAt, String status,
                        boolean confirmed, int remainingAttempts, boolean canOpen, boolean canStart) {}
    static String formatCode(int value) { return String.format(Locale.ROOT,"%06d",value); }
    private Party lock(long id) {
        return parties.findByIdForUpdate(id).orElseThrow(()->ApiException.notFound("없는 일행입니다."));
    }
    private RideRequest member(Party p,long actor) {
        return requests.findByPartyOrderByRideOrder(p).stream().filter(r->r.getMember().getId()==actor).findFirst()
            .orElseThrow(()->ApiException.forbidden("현재 일행원만 사용할 수 있어요."));
    }
    private boolean ready(Party p) {
        return p.paymentFlow() && p.canChangeBeforeRide() && List.of(PartyStatus.READY,PartyStatus.RIDE_UNCONFIRMED).contains(p.getStatus())
            && p.getCalledAt()!=null && p.getPendingJoinId()==null && !p.isRouteVerificationPending();
    }
    @Transactional
    public void open(long actor,long id) {
        Party p=lock(id); RideRequest r=member(p,actor);
        if(p.getPayer().getId()!=actor) throw ApiException.forbidden("결제자만 실제 탑승을 표시할 수 있어요.");
        if(p.getBoardingCode()!=null && ready(p) && r.getStatus()==RequestStatus.MATCHED) return; // retries never rotate or extend a generation
        if(!ready(p) || r.isNoShow() || r.getStatus()!=RequestStatus.MATCHED) throw ApiException.conflict("BOARDING_NOT_READY","선결제와 호출을 확인해 주세요.");
        p.setBoardingGeneration(p.getBoardingGeneration()+1);
        p.setBoardingCode(formatCode(RANDOM.nextInt(1_000_000)));
        p.setBoardedAt(clock.now()); p.setBoardingDeadlineAt(p.getBoardedAt().plusSeconds(300)); p.setBoardingClosedAt(null);
        r.setBoardingConfirmedAt(clock.now()); r.setArrived(true);
        chat.system(p,"결제자가 택시에 탔어요. 탑승한 일행은 결제자 화면의 코드를 5분 안에 입력해 주세요.");
    }
    // A rejected attempt must commit even when the request or an outer transaction rolls back.
    @Transactional(propagation=Propagation.REQUIRES_NEW, isolation=Isolation.READ_COMMITTED, noRollbackFor=ApiException.class)
    public void confirm(long actor,long id,String code,int generation) {
        Party p=lock(id); RideRequest r=member(p,actor);
        if(p.getPayer().getId()==actor) throw ApiException.forbidden("결제자는 실제 탑승 버튼을 사용해 주세요.");
        if(p.getBoardingCode()==null || generation!=p.getBoardingGeneration()) throw ApiException.conflict("CODE_GENERATION","코드가 바뀌었어요. 화면을 새로고침해 주세요.");
        // Only a historical successful retry is allowed after the window closed normally.
        boolean active=List.of(PartyStatus.READY,PartyStatus.RIDE_UNCONFIRMED,PartyStatus.AWAITING_FARE).contains(p.getStatus());
        if(!active || r.isNoShow() || !List.of(RequestStatus.MATCHED,RequestStatus.COMPLETED).contains(r.getStatus()))
            throw ApiException.conflict("BOARDING_ENDED","종료되거나 취소된 탑승은 확인할 수 없어요.");
        if(r.getBoardingConfirmedAt()!=null) return;
        if(r.getStatus()!=RequestStatus.MATCHED) throw ApiException.conflict("BOARDING_ENDED","종료된 요청입니다.");
        if(p.isRouteVerificationPending()) throw ApiException.conflict("ROUTE_RETRY_PENDING","일행 경로를 다시 확인한 뒤 탑승할 수 있어요.");
        if(!clock.now().isBefore(p.getBoardingDeadlineAt())) throw ApiException.conflict("CODE_EXPIRED","코드 입력 시간이 끝났어요. 탑승 미확인은 조사로 확인해요.");
        BoardingAttempt a=attempts.findByPartyIdAndMemberIdAndGeneration(id,actor,generation).orElseGet(()->{
            BoardingAttempt created=new BoardingAttempt(); created.setPartyId(id); created.setMemberId(actor); created.setGeneration(generation); return created;
        });
        if(a.getFailures()>=5) throw ApiException.conflict("CODE_LOCKED","이번 코드의 입력 기회를 모두 사용했어요.");
        if(code==null || !code.matches("[0-9]{6}") || !p.getBoardingCode().equals(code)) {
            a.setFailures(a.getFailures()+1); attempts.saveAndFlush(a);
            throw ApiException.conflict("CODE_MISMATCH","코드가 맞지 않아요. 남은 횟수: "+(5-a.getFailures()));
        }
        r.setBoardingConfirmedAt(clock.now()); r.setArrived(true);
        chat.system(p,actor,"%s님의 탑승이 확인됐어요.");
    }
    @Transactional
    public void start(long actor,long id) {
        Party p=lock(id); RideRequest rider=member(p,actor);
        if(p.getPayer().getId()!=actor) throw ApiException.forbidden("결제자만 운행을 시작할 수 있어요.");
        if(p.getStartedAt()!=null && List.of(PartyStatus.AWAITING_FARE,PartyStatus.OBJECTION_WINDOW,PartyStatus.SETTLED,PartyStatus.COMPLETED).contains(p.getStatus())) return;
        if(!ready(p) || rider.getStatus()!=RequestStatus.MATCHED || p.getBoardedAt()==null || p.getBoardingCode()==null) throw ApiException.conflict("BOARD_FIRST","실제 탑승을 먼저 표시해 주세요.");
        rides.start(p,clock.now());
    }
    @Transactional(readOnly=true)
    public State state(long actor,long id) {
        Party p=parties.findById(id).orElseThrow(()->ApiException.notFound("없는 일행입니다.")); RideRequest r=member(p,actor);
        boolean payer=p.getPayer().getId()==actor;
        boolean active=List.of(PartyStatus.READY,PartyStatus.RIDE_UNCONFIRMED,PartyStatus.AWAITING_FARE).contains(p.getStatus()) && r.getStatus()==RequestStatus.MATCHED;
        boolean open=active && p.getBoardingDeadlineAt()!=null && clock.now().isBefore(p.getBoardingDeadlineAt());
        int failures=attempts.findByPartyIdAndMemberIdAndGeneration(id,actor,p.getBoardingGeneration()).map(BoardingAttempt::getFailures).orElse(0);
        return new State(p.getBoardingGeneration(),payer&&open?p.getBoardingCode():null,p.getBoardingDeadlineAt(),
            p.getBoardedAt()==null?"WAITING":open?"OPEN":"EXPIRED",r.getBoardingConfirmedAt()!=null,5-failures,
            payer&&active&&ready(p)&&p.getBoardedAt()==null,payer&&active&&ready(p)&&p.getBoardedAt()!=null);
    }
}
