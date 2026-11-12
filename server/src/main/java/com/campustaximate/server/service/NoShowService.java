package com.campustaximate.server.service;

import com.campustaximate.server.common.*;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import com.campustaximate.settlement.SettlementCalculator;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 노쇼 기록은 조사 결과가 확정된 때에만 남긴다. 모든 변경은 일행 잠금 아래 수행한다. */
@Service @RequiredArgsConstructor
public class NoShowService {
    private final PartyRepository parties; private final RideRequestRepository requests;
    private final RideConfirmationRepository rideAnswers;
    private final PartyMemberTermRepository terms; private final BoardingCaseRepository cases;
    private final BoardingVoteRepository votes; private final BoardingExplanationRepository explanations;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private final MemberRepository members; private final MemberSafetyService safety;
    private final PartyChangeService changes; private final PaymentService payments;
    private final NotificationService notifications; private final AppClock clock;

    public record CaseView(BoardingCase investigation, List<BoardingVote> votes, BoardingExplanation explanation) {}
    @Transactional(readOnly=true)
    public List<CaseView> list(long actor,long partyId) {
        requireMember(actor,partyId);
        return cases.findByPartyIdOrderByRequestId(partyId).stream().map(c->new CaseView(c,votes.findByCaseIdOrderById(c.getId()),explanations.findByCaseId(c.getId()).orElse(null))).toList();
    }
    @Transactional(readOnly=true)
    public List<CaseView> mine(long actor) {
        return cases.findByMemberIdOrderByIdDesc(actor).stream().map(c->new CaseView(c,votes.findByCaseIdOrderById(c.getId()),explanations.findByCaseId(c.getId()).orElse(null))).toList();
    }
    public void requireMember(long actor,long partyId) {
        if(terms.findByPartyIdAndMemberId(partyId,actor).isEmpty()) throw ApiException.forbidden("배정된 일행원만 볼 수 있어요.");
    }
    private Party lock(long id) {
        Party party=parties.findByIdForUpdate(id).orElseThrow(()->ApiException.notFound("없는 일행입니다."));
        if(!party.paymentFlow()) throw ApiException.conflict("LEGACY_PARTY","기존 일행은 기존 노쇼 규칙을 사용해요.");
        return party;
    }
    private BoardingCase open(Party party,PartyMemberTerm term,boolean originalPayer) {
        return cases.findByPartyIdAndRequestId(party.getId(),term.getRequestId()).orElseGet(()->{
            BoardingCase c=new BoardingCase(); c.setPartyId(party.getId()); c.setRequestId(term.getRequestId()); c.setMemberId(term.getMemberId());
            c.setKind(originalPayer?"ORIGINAL_PAYER":"NO_SHOW"); c.setStatus("PENDING"); c.setNoCompensation(originalPayer);
            Instant opened=term.getBoardingUnconfirmedAt()==null?clock.now():term.getBoardingUnconfirmedAt();
            term.setBoardingUnconfirmedAt(opened); term.setRefundHeld(true);
            c.setOpenedAt(opened); c.setDeadlineAt(opened.plusSeconds(86400));
            notifications.toMember(members.getReferenceById(term.getMemberId()),"[캠퍼스 택시메이트] 탑승 확인 안내","탑승이 확인되지 않았어요. 24시간 안에 앱에서 소명할 수 있어요.");
            return cases.save(c);
        });
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void beginRide(Party party,Instant started) {
        if(party.getStartedAt()!=null) return;
        var riders=requests.findByPartyOrderByRideOrder(party);
        changes.capture(party,riders,true);
        // 결제자의 운행 시작 선언/영수증은 본인이 탔다는 표시다.
        var declared=rideAnswers.findByPartyIdOrderById(party.getId()).stream().filter(RideConfirmation::isRode).map(RideConfirmation::getMemberId).toList();
        riders.stream().filter(r->r.getMember().getId().equals(party.getPayer().getId())||declared.contains(r.getMember().getId())).forEach(r->r.setArrived(true));
        Set<Long> missing=new HashSet<>();
        for(var rider:riders) if(!rider.isArrived()) {
            var term=terms.findByPartyIdAndRequestId(party.getId(),rider.getId()).orElseThrow();
            open(party,term,Objects.equals(party.getOriginalPayerId(),term.getMemberId())); missing.add(rider.getId());
        }
        if(!missing.isEmpty()) {
            changes.changed(party,missing,"BOARDING_UNCONFIRMED",missing);
            for(var rider:riders) if(missing.contains(rider.getId())) { rider.clearParty(); rider.finish(RequestStatus.CANCELED); }
        }
        var remaining=riders.stream().filter(r->!missing.contains(r.getId())).toList();
        // 운행이 이미 시작됐으므로 한 명이어도 실제 요금을 정산한다. 단독 값은 최초 조회값을 보존한다.
        if(!missing.isEmpty()) {
            int payer=java.util.stream.IntStream.range(0,remaining.size()).filter(i->remaining.get(i).getMember().getId().equals(party.getPayer().getId())).findFirst().orElseThrow();
            var current=SettlementCalculator.shares(party.getExpectedTotal(),remaining.stream().map(RideRequest::getRatioUnits).toList(),payer);
            for(int i=0;i<remaining.size();i++){remaining.get(i).setExpectedShare(current.get(i));remaining.get(i).setRideOrder(i+1);}
        }
        remaining.forEach(r->r.finish(RequestStatus.COMPLETED)); party.setStartedAt(started); party.setStatus(PartyStatus.AWAITING_FARE);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void vote(long actor,long partyId,long target,boolean came) {
        Party party=lock(partyId); requireMember(actor,partyId);
        if(actor==target) throw ApiException.badRequest("SELF_TARGET","본인은 표시할 수 없어요.");
        if(terms.findByPartyIdAndMemberId(partyId,actor).orElseThrow().getExitedAt()!=null) throw ApiException.forbidden("현재 일행원만 표시할 수 있어요.");
        var term=terms.findByPartyIdAndMemberId(partyId,target).orElseThrow(()->ApiException.notFound("없는 일행원입니다."));
        var c=cases.findByPartyIdAndRequestId(partyId,term.getRequestId()).orElse(null);
        if(c==null) {
            var request=requests.findById(term.getRequestId()).orElseThrow();
            boolean payerMissing=target==party.getPayer().getId() && party.getStartedAt()==null && !request.isArrived() && !clock.now().isBefore(party.getDepartureAt())
                    && List.of(PartyStatus.READY,PartyStatus.RIDE_UNCONFIRMED,PartyStatus.PAYER_PENDING).contains(party.getStatus());
            if(!payerMissing || came) throw ApiException.conflict("NOT_UNCONFIRMED","탑승 미확인 대상이 아니에요.");
            c=open(party,term,true);
        }
        var old=votes.findByCaseIdAndActorId(c.getId(),actor).orElse(null);
        if(old!=null) {if(old.isCame()!=came)throw ApiException.conflict("ANSWER_FINAL","첫 표시는 바꿀 수 없어요.");return;}
        if(!c.pending() || !clock.now().isBefore(c.getDeadlineAt())) throw ApiException.conflict("VOTE_CLOSED","일행 표시 기한이 끝났어요.");
        BoardingVote vote=new BoardingVote();vote.setCaseId(c.getId());vote.setActorId(actor);vote.setCame(came);vote.setCreatedAt(clock.now());votes.save(vote);
        if("ORIGINAL_PAYER".equals(c.getKind()) && !came && party.getStartedAt()==null && party.getSuccessionStatus()==null) offer(party,c);
    }
    private void offer(Party party,BoardingCase c) {
        var remaining=requests.findByPartyOrderByRideOrder(party).stream().filter(r->!r.getId().equals(c.getRequestId())).toList();
        var next=remaining.stream().max(Comparator.comparing(RideRequest::getRideOrder)).orElse(null);
        party.setSuccessionStatus("OFFERED");party.setSuccessionDeadlineAt(party.getDepartureAt().plusSeconds(300));
        if(next==null || !clock.now().isBefore(party.getSuccessionDeadlineAt())) { failSuccession(party);return; }
        party.setSuccessorId(next.getMember().getId());party.setStatus(PartyStatus.PAYER_PENDING);
        notifications.toMember(next.getMember(),"[캠퍼스 택시메이트] 결제자 승계 요청","원래 결제자의 도착이 확인되지 않았어요. 출발 시각 5분 뒤까지 결제자 승계를 수락해 주세요.");
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void succession(long actor,long partyId,boolean accept) {
        Party party=lock(partyId);
        if(!Objects.equals(party.getSuccessorId(),actor)) throw ApiException.forbidden("승계를 요청받은 사람만 답할 수 있어요.");
        String decision=accept?"ACCEPTED":"REFUSED";
        if(decision.equals(party.getSuccessionStatus())) return;
        if(!"OFFERED".equals(party.getSuccessionStatus())) throw ApiException.conflict("SUCCESSION_FINAL","승계 처리가 끝났어요.");
        if(!accept || !clock.now().isBefore(party.getSuccessionDeadlineAt())) {failSuccession(party);return;}
        var original=requests.findByPartyOrderByRideOrder(party).stream().filter(r->r.getMember().getId().equals(party.getPayer().getId())).findFirst().orElseThrow();
        changes.changed(party,Set.of(original.getId()),"PAYER_NO_SHOW",Set.of(original.getId()));
        original.clearParty();original.finish(RequestStatus.CANCELED);
        party.setPayer(members.getReferenceById(actor));party.setSuccessionStatus("ACCEPTED");
        beginRide(party,clock.now());
    }
    private void failSuccession(Party party) {
        party.setSuccessionStatus("REFUSED");party.setStatus(PartyStatus.NOT_OPERATED);
        requests.findByPartyOrderByRideOrder(party).forEach(r->r.finish(RequestStatus.CANCELED));
        payments.refundAll(party.getId(),"결제자 승계 미성사");
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void expireSuccession(long partyId) {
        Party party=lock(partyId);
        if("OFFERED".equals(party.getSuccessionStatus()) && !clock.now().isBefore(party.getSuccessionDeadlineAt())) failSuccession(party);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void explain(long actor,long caseId,String text) {
        BoardingCase ref=cases.findById(caseId).orElseThrow(()->ApiException.notFound("없는 조사입니다."));
        lock(ref.getPartyId()); var c=cases.findById(caseId).orElseThrow();entityManager.refresh(c,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if(c.getMemberId()!=actor) throw ApiException.forbidden("본인 조사에만 소명할 수 있어요.");
        if(text==null || text.isBlank() || text.length()>2000)throw ApiException.badRequest("INVALID_EXPLANATION","소명은 1~2000자로 입력해 주세요.");
        var old=explanations.findByCaseId(caseId).orElse(null);
        if(old!=null) {if(!old.getExplanation().equals(text.strip()))throw ApiException.conflict("ANSWER_FINAL","첫 소명은 바꿀 수 없어요.");return;}
        if(!c.pending() || !clock.now().isBefore(c.getDeadlineAt())) throw ApiException.conflict("EXPLANATION_CLOSED","24시간 소명 기한이 끝났어요.");
        BoardingExplanation e=new BoardingExplanation();e.setCaseId(caseId);e.setMemberId(actor);e.setExplanation(text.strip());e.setCreatedAt(clock.now());explanations.save(e);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void adjudicate(long id) {
        var reference=cases.findById(id).orElseThrow();Party party=lock(reference.getPartyId());
        if(party.getStatus()==PartyStatus.REVIEW_PENDING && "RIDE_DISPUTE".equals(party.getReviewReason()))return;
        var c=cases.findById(id).orElseThrow();entityManager.refresh(c,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if(!"PENDING".equals(c.getStatus()) || clock.now().isBefore(c.getDeadlineAt()))return;
        var reports=votes.findByCaseIdOrderById(id);
        if(!reports.isEmpty() && reports.stream().noneMatch(BoardingVote::isCame) && explanations.findByCaseId(id).isEmpty()) decide(c,true,null,"24시간 동안 부재 표시만 있고 소명 없음");
        else c.setStatus("REVIEW");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void decide(BoardingCase c,boolean noShow,Long admin,String reason) {
        if(!c.pending()) return;
        c.setStatus(noShow?"CONFIRMED":"EXONERATED");c.setDecidedAt(clock.now());c.setDecidedBy(admin);c.setDecisionReason(reason);
        if(noShow) {
            members.findByIdForUpdate(c.getMemberId()).orElseThrow();
            safety.recordNoShow(requests.findById(c.getRequestId()).orElseThrow(),clock.now());
        }
    }
    public boolean unresolved(long partyId){return cases.findByPartyIdOrderByRequestId(partyId).stream().anyMatch(BoardingCase::pending);}
    @Transactional(propagation=Propagation.MANDATORY)
    public void notOperated(Party party) {
        // 승계 거부 때 원래 결제자의 조사는 별도로 계속한다. 기사 거절/배차 실패는 노쇼가 아니다.
        for(var c:cases.findByPartyIdOrderByRequestId(party.getId())) if(c.pending() && !("ORIGINAL_PAYER".equals(c.getKind())&&"REFUSED".equals(party.getSuccessionStatus()))) decide(c,false,null,"운행 미성사");
    }
}
