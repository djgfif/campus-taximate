package com.campustaximate.server.service;
import com.campustaximate.server.common.*;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 결론과 근거를 자동 정산과 같은 일행 잠금 순서로 기록한다. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class AdminService {
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private final MemberRepository members; private final PartyRepository parties;
    private final BoardingCaseRepository cases; private final BoardingVoteRepository votes;
    private final BoardingExplanationRepository explanations; private final PartyMemberTermRepository terms;
    private final PartyMessageRepository messages; private final SettlementRepository settlements;
    private final FareObjectionRepository objections; private final NoShowService noShows;
    private final RideConfirmationService rides; private final SettlementService settlementService;
    private final AdminAuditRepository audits; private final MemberReportRepository reports;
    private final MemberRestrictionRepository restrictions; private final PaymentOrderRepository orders;
    private final HubRepository hubs; private final SchoolRepository schools; private final AppClock clock;
    private final RideRequestRepository requests;
    public void requireAdmin(long actor) {
        if(!members.findById(actor).map(Member::isAdmin).orElse(false))throw ApiException.forbidden("관리자만 이용할 수 있어요.");
    }
    public record CaseSummary(BoardingCase investigation,PartyMemberTerm boarding,boolean receiptPresent,long absentVotes,long presentVotes,boolean explanationPresent) {}
    public record ChatLine(long id,long memberId,String text,Instant createdAt) {}
    public record CaseDetail(CaseSummary summary,List<BoardingVote> votes,BoardingExplanation explanation,List<ChatLine> chat) {}
    public List<CaseSummary> noShowReviews(long actor) {
        requireAdmin(actor);
        return cases.findAll().stream().filter(c->"REVIEW".equals(c.getStatus())).map(this::summary).toList();
    }
    private CaseSummary summary(BoardingCase c) {
        Party p=parties.findById(c.getPartyId()).orElseThrow();
        boolean receipt=settlements.findByParty(p).map(s->s.getReceiptPath()!=null).orElse(false);
        var evidence=votes.findByCaseIdOrderById(c.getId());
        return new CaseSummary(c,terms.findByPartyIdAndRequestId(c.getPartyId(),c.getRequestId()).orElseThrow(),receipt,evidence.stream().filter(v->!v.isCame()).count(),evidence.stream().filter(BoardingVote::isCame).count(),explanations.findByCaseId(c.getId()).isPresent());
    }
    public CaseDetail noShowDetail(long actor,long id) {
        requireAdmin(actor);var c=cases.findById(id).orElseThrow(()->ApiException.notFound("없는 조사입니다."));
        return new CaseDetail(summary(c),votes.findByCaseIdOrderById(id),explanations.findByCaseId(id).orElse(null),
                // 판정 근거는 일행원이 쓴 대화만. 시스템 알림(보낸 사람 없음)은 뺀다.
                messages.findTop50ByPartyIdOrderByIdDesc(c.getPartyId()).stream().filter(m->m.getMember()!=null).map(m->new ChatLine(m.getId(),m.getMember().getId(),m.getText(),m.getCreatedAt())).toList());
    }
    private Party lockParty(long id){return parties.findByIdForUpdate(id).orElseThrow(()->ApiException.notFound("없는 일행입니다."));}
    private static void evidence(String s){if(s==null||s.isBlank()||s.length()>2000)throw ApiException.badRequest("EVIDENCE_REQUIRED","판정 근거를 1~2000자로 입력해 주세요.");}
    private String fingerprint(String text){
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private boolean done(String key,String fingerprint){
        var old=audits.findByOperationKey(key).orElse(null);
        if(old==null)return false;
        if(!Objects.equals(old.getFingerprint(),fingerprint(fingerprint)))throw ApiException.conflict("DECISION_FINAL","이미 다른 내용으로 처리된 요청이에요.");
        return true;
    }
    private void audit(long actor,String action,String type,long id,String decision,String evidence,String key,String fingerprint){
        evidence(evidence);AdminAudit a=new AdminAudit();a.setActorId(actor);a.setCreatedAt(clock.now());a.setAction(action);a.setTargetType(type);a.setTargetId(id);
        a.setDecision(decision);a.setEvidence(evidence.strip());a.setOperationKey(key);a.setFingerprint(fingerprint(fingerprint));audits.save(a);
    }
    private String key(long actor,String action,String key){if(key==null||key.isBlank()||key.length()>100)throw ApiException.badRequest("IDEMPOTENCY_REQUIRED","100자 이하의 멱등키가 필요해요.");return actor+":"+action+":"+key;}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public long decideNoShow(long actor,long id,String decision,String evidence){
        requireAdmin(actor);evidence(evidence);
        var ref=cases.findById(id).orElseThrow(()->ApiException.notFound("없는 조사입니다."));Party party=lockParty(ref.getPartyId());
        if(party.getStatus()==PartyStatus.REVIEW_PENDING && "RIDE_DISPUTE".equals(party.getReviewReason()))throw ApiException.conflict("RIDE_REVIEW_REQUIRED","운행 여부를 먼저 확인해 주세요.");
        String resolved=switch(decision){case "NO_SHOW"->"NO_SHOW";case "NOT_NO_SHOW","UNDETERMINED"->"NOT_NO_SHOW";default->throw ApiException.badRequest("INVALID_DECISION","노쇼 여부를 선택해 주세요.");};
        String k="NO_SHOW:"+id,f=resolved+":"+evidence.strip();if(done(k,f))return party.getId();
        var c=cases.findById(id).orElseThrow();entityManager.refresh(c,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if(clock.now().isBefore(c.getDeadlineAt()))throw ApiException.conflict("NOT_REVIEWABLE","24시간 소명 기한 뒤에 판정할 수 있어요.");
        if(!c.pending()) {
            boolean same="CONFIRMED".equals(c.getStatus())=="NO_SHOW".equals(resolved);
            if(!same || "LATE_CANCEL".equals(c.getKind()))throw ApiException.conflict("DECISION_FINAL","이미 확정된 다른 판정은 바꿀 수 없어요.");
        } else noShows.decide(c,"NO_SHOW".equals(resolved),actor,evidence);
        audit(actor,"NO_SHOW_DECISION","BOARDING_CASE",id,resolved,evidence,k,f);return party.getId();
    }
    public record FareReview(long partyId,PartyStatus status,String reason,Long settlementId,Long amount,boolean receiptPresent,List<FareObjection> objections) {}
    public List<FareReview> fareReviews(long actor){requireAdmin(actor);return parties.findByStatus(PartyStatus.REVIEW_PENDING).stream().map(p->{var s=settlements.findByParty(p).orElse(null);return new FareReview(p.getId(),p.getStatus(),p.getReviewReason(),s==null?null:s.getId(),s==null?null:s.getPaidAmount(),s!=null&&s.getReceiptPath()!=null,s==null?List.of():objections.findBySettlementIdOrderById(s.getId()));}).toList();}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void decideFare(long actor,long partyId,boolean accept,Long correctedAmount,String evidence){
        requireAdmin(actor);evidence(evidence);Party party=lockParty(partyId);
        var s=settlements.findByParty(party).orElseThrow(()->ApiException.notFound("정산이 없습니다."));
        String k="FARE:"+s.getId(),f=accept+":"+correctedAmount+":"+evidence.strip();if(done(k,f))return;
        if(party.getStatus()!=PartyStatus.REVIEW_PENDING||"RIDE_DISPUTE".equals(party.getReviewReason())||!objections.existsBySettlementIdAndResolvedAtIsNull(s.getId()))throw ApiException.conflict("NOT_REVIEWABLE","요금 이의 검토 대상이 아니에요.");
        if(accept){if(correctedAmount==null)throw ApiException.badRequest("AMOUNT_REQUIRED","확정할 실제 요금을 입력해 주세요.");settlementService.correctByAdmin(party,s,correctedAmount);}
        else if(correctedAmount!=null)throw ApiException.badRequest("INVALID_AMOUNT","기각할 때는 수정 금액을 넣지 마세요.");
        for(var o:objections.findBySettlementIdOrderById(s.getId()))if(o.getResolvedAt()==null){o.setResolvedAt(clock.now());o.setResolution(accept?"ACCEPTED":"REJECTED");o.setAdminId(actor);}
        party.setStatus(PartyStatus.OBJECTION_WINDOW);party.setAdminReviewRequired(false);party.setReviewReason(null);
        s.setObjectionDeadlineAt(clock.now());audit(actor,"FARE_DECISION","PARTY",partyId,accept?"ACCEPTED":"REJECTED",evidence,k,f);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void decideRide(long actor,long partyId,String evidence){
        requireAdmin(actor);evidence(evidence);Party party=lockParty(partyId);
        String k="RIDE:"+partyId,f=evidence.strip();if(done(k,f))return;
        if(party.getStatus()!=PartyStatus.REVIEW_PENDING||!"RIDE_DISPUTE".equals(party.getReviewReason()))throw ApiException.conflict("NOT_REVIEWABLE","운행 여부 검토 대상이 아니에요.");
        var s=settlements.findByParty(party).orElse(null);boolean operated=s!=null&&s.getReceiptPath()!=null;
        if(operated){
            if(party.getStartedAt()==null)rides.start(party,party.getDepartureAt());
            settlementService.correctByAdmin(party,s,s.getPaidAmount());
            // 요금 이의가 따로 있으면 계속 검토한다.
            if(objections.existsBySettlementIdAndResolvedAtIsNull(s.getId()))party.setStatus(PartyStatus.REVIEW_PENDING);
            else {party.setStatus(PartyStatus.OBJECTION_WINDOW);}
        }else rides.notOperated(party);
        party.setAdminReviewRequired(false);party.setReviewReason(null);
        audit(actor,"RIDE_DECISION","PARTY",partyId,operated?"OPERATED":"NOT_OPERATED",evidence,k,f);
    }
    public record ReportView(long id,long partyId,long reporterId,long reportedId,String reason,String memo,Instant createdAt) {}
    public List<ReportView> reports(long actor){requireAdmin(actor);return reports.findAll().stream().map(r->new ReportView(r.getId(),r.getParty().getId(),r.getReporterId(),r.getReportedId(),r.getReason().name(),r.getMemo(),r.getCreatedAt())).toList();}
    public List<MemberRestriction> restrictions(long actor,long memberId){requireAdmin(actor);return restrictions.findAll().stream().filter(r->r.getMemberId()==memberId).toList();}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public MemberRestriction restrict(long actor,long memberId,Instant until,String evidence,String idempotency){
        requireAdmin(actor);evidence(evidence);members.findByIdForUpdate(memberId).orElseThrow(()->ApiException.notFound("없는 회원입니다."));
        String k=key(actor,"RESTRICT",idempotency),f=memberId+":"+until+":"+evidence.strip();
        if(done(k,f))return restrictions.findById(audits.findByOperationKey(k).orElseThrow().getTargetId()).orElseThrow();
        if(until==null||!until.isAfter(clock.now()))throw ApiException.badRequest("INVALID_DEADLINE","제한 만료는 미래 시각이어야 해요.");
        MemberRestriction r=new MemberRestriction();r.setMemberId(memberId);r.setReason("MANUAL");r.setCreatedAt(clock.now());r.setExpiresAt(until);restrictions.save(r);
        audit(actor,"RESTRICT","RESTRICTION",r.getId(),"GRANTED",evidence,k,f);return r;
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void release(long actor,long restrictionId,String evidence){
        requireAdmin(actor);evidence(evidence);var ref=restrictions.findById(restrictionId).orElseThrow(()->ApiException.notFound("없는 제한입니다."));members.findByIdForUpdate(ref.getMemberId()).orElseThrow();
        String k="RELEASE:"+restrictionId,f=evidence.strip();if(done(k,f))return;
        ref.setExpiresAt(clock.now());audit(actor,"RELEASE","RESTRICTION",restrictionId,"RELEASED",evidence,k,f);
    }
    public List<PaymentOrder> overdue(long actor){requireAdmin(actor);return orders.findAll().stream().filter(o->o.getPurpose()==PaymentPurpose.ADDITIONAL&&o.getStatus()==PaymentStatus.OVERDUE).toList();}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void handleOverdue(long actor,long id,String evidence){
        requireAdmin(actor);evidence(evidence);var ref=orders.findById(id).orElseThrow(()->ApiException.notFound("없는 결제입니다."));lockParty(ref.getPartyId());var o=orders.lockById(id).orElseThrow();
        String k="OVERDUE:"+id,f=evidence.strip();if(done(k,f))return;
        if(o.getStatus()!=PaymentStatus.OVERDUE)throw ApiException.conflict("NOT_OVERDUE","미정산 결제가 아니에요.");
        // 처리 표시는 승인이나 채무 면제로 간주하지 않는다.
        o.setAdminHandledAt(clock.now());o.setAdminHandledBy(actor);o.setAdminNote(evidence.strip());audit(actor,"OVERDUE_HANDLED","PAYMENT_ORDER",id,"HANDLED",evidence,k,f);
    }
    public record HubInput(String name,String meetingPoint,double lat,double lon,Set<Long> schoolIds,boolean active,boolean fieldChecked) {}
    public record HubView(long id,String name,String meetingPoint,double lat,double lon,Set<Long> schoolIds,boolean active,boolean fieldChecked) {}
    private HubView view(Hub h){return new HubView(h.getId(),h.getName(),h.getMeetingPoint(),h.getLat(),h.getLon(),h.getSchools().stream().map(School::getId).collect(java.util.stream.Collectors.toSet()),h.isActive(),h.isFieldChecked());}
    public List<HubView> hubs(long actor){requireAdmin(actor);return hubs.findAll().stream().map(this::view).toList();}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public HubView saveHub(long actor,Long id,HubInput input,String evidence,String idempotency){
        requireAdmin(actor);evidence(evidence);members.findByIdForUpdate(actor).orElseThrow();
        if(input==null||input.name()==null||input.name().isBlank()||input.name().length()>100||input.meetingPoint()==null||input.meetingPoint().isBlank()||input.meetingPoint().length()>255||!Double.isFinite(input.lat())||!Double.isFinite(input.lon())||Math.abs(input.lat())>90||Math.abs(input.lon())>180||input.schoolIds()==null||input.schoolIds().isEmpty()||input.schoolIds().stream().anyMatch(idValue->idValue==null||idValue<=0))throw ApiException.badRequest("INVALID_HUB","지점 이름·위치·학교를 확인해 주세요.");
        String k=key(actor,"HUB",idempotency),f=id+":"+input.name()+":"+input.meetingPoint()+":"+input.lat()+":"+input.lon()+":"+new TreeSet<>(input.schoolIds())+":"+input.active()+":"+input.fieldChecked()+":"+evidence.strip();
        if(done(k,f))return view(hubs.findById(audits.findByOperationKey(k).orElseThrow().getTargetId()).orElseThrow());
        var selected=schools.findAllById(input.schoolIds());if(selected.size()!=input.schoolIds().size())throw ApiException.badRequest("INVALID_SCHOOL","등록된 학교를 선택해 주세요.");
        Hub h=id==null?new Hub():hubs.findByIdForUpdate(id).orElseThrow(()->ApiException.notFound("없는 승차 지점입니다."));
        h.setName(input.name().strip());h.setMeetingPoint(input.meetingPoint().strip());h.setLat(input.lat());h.setLon(input.lon());h.setSchools(new HashSet<>(selected));h.setActive(input.active());h.setFieldChecked(input.fieldChecked());hubs.save(h);
        audit(actor,"HUB_SAVE","HUB",h.getId(),id==null?"CREATED":"UPDATED",evidence,k,f);return view(h);
    }
    public Settlement receiptOf(long actor,long partyId){
        requireAdmin(actor);Party p=parties.findById(partyId).orElseThrow(()->ApiException.notFound("없는 일행입니다."));
        return settlements.findByParty(p).filter(s->s.getReceiptPath()!=null).orElseThrow(()->ApiException.notFound("보관 중인 영수증이 없습니다."));
    }
    public List<AdminAudit> auditLog(long actor){requireAdmin(actor);return audits.findAll();}
}
