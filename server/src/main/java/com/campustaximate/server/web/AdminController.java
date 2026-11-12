package com.campustaximate.server.web;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.service.*;
import com.campustaximate.server.domain.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 모든 경로는 관리자 인터셉터와 서비스 권한 검사를 함께 통과해야 한다. */
@RestController @RequestMapping("/api/admin") @RequiredArgsConstructor
public class AdminController {
    private final AdminService admin; private final PaymentLifecycleService lifecycle;
    private long actor(HttpSession s){Object id=s.getAttribute(LoginTokenFilter.SESSION_KEY);if(!(id instanceof Long))throw ApiException.unauthorized();return (Long)id;}
    public record DecisionBody(@NotBlank String decision,@NotBlank @Size(max=2000) String evidence) {}
    public record EvidenceBody(@NotBlank @Size(max=2000) String evidence) {}
    public record FareBody(@NotNull Boolean accept,Long correctedAmount,@NotBlank @Size(max=2000) String evidence) {}
    public record RestrictionBody(@NotNull Instant until,@NotBlank @Size(max=2000) String evidence) {}
    public record HubBody(@NotNull @Valid AdminService.HubInput hub,@NotBlank @Size(max=2000) String evidence) {}
    @GetMapping("/no-shows") public List<AdminService.CaseSummary> noShows(HttpSession s){return admin.noShowReviews(actor(s));}
    @GetMapping("/no-shows/{id}") public AdminService.CaseDetail noShow(@PathVariable long id,HttpSession s){return admin.noShowDetail(actor(s),id);}
    @PostMapping("/no-shows/{id}/decision") public void decideNoShow(@PathVariable long id,@Valid @RequestBody DecisionBody b,HttpSession s){admin.decideNoShow(actor(s),id,b.decision(),b.evidence());lifecycle.afterDepartures();}
    @GetMapping("/fare-reviews") public List<AdminService.FareReview> fareReviews(HttpSession s){return admin.fareReviews(actor(s));}
    @PostMapping("/parties/{id}/fare-decision") public void fare(@PathVariable long id,@Valid @RequestBody FareBody b,HttpSession s){admin.decideFare(actor(s),id,b.accept(),b.correctedAmount(),b.evidence());lifecycle.afterDepartures();}
    @PostMapping("/parties/{id}/ride-decision") public void ride(@PathVariable long id,@Valid @RequestBody EvidenceBody b,HttpSession s){admin.decideRide(actor(s),id,b.evidence());lifecycle.afterDepartures();}
    @GetMapping("/reports") public List<AdminService.ReportView> reports(HttpSession s){return admin.reports(actor(s));}
    @GetMapping("/members/{id}/restrictions") public List<MemberRestriction> restrictions(@PathVariable long id,HttpSession s){return admin.restrictions(actor(s),id);}
    @PostMapping("/members/{id}/restrictions") public MemberRestriction restrict(@PathVariable long id,@Valid @RequestBody RestrictionBody b,@RequestHeader("Idempotency-Key") String key,HttpSession s){return admin.restrict(actor(s),id,b.until(),b.evidence(),key);}
    @PostMapping("/restrictions/{id}/release") public void release(@PathVariable long id,@Valid @RequestBody EvidenceBody b,HttpSession s){admin.release(actor(s),id,b.evidence());}
    @GetMapping("/overdue-payments") public List<PaymentOrder> overdue(HttpSession s){return admin.overdue(actor(s));}
    @PostMapping("/overdue-payments/{id}/handled") public void handled(@PathVariable long id,@Valid @RequestBody EvidenceBody b,HttpSession s){admin.handleOverdue(actor(s),id,b.evidence());}
    @GetMapping("/hubs") public List<AdminService.HubView> hubs(HttpSession s){return admin.hubs(actor(s));}
    @PostMapping("/hubs") public AdminService.HubView createHub(@Valid @RequestBody HubBody b,@RequestHeader("Idempotency-Key") String key,HttpSession s){return admin.saveHub(actor(s),null,b.hub(),b.evidence(),key);}
    @PutMapping("/hubs/{id}") public AdminService.HubView updateHub(@PathVariable long id,@Valid @RequestBody HubBody b,@RequestHeader("Idempotency-Key") String key,HttpSession s){return admin.saveHub(actor(s),id,b.hub(),b.evidence(),key);}
    @GetMapping("/parties/{id}/receipt")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> receipt(@PathVariable long id,HttpSession s){
        var settlement=admin.receiptOf(actor(s),id);
        org.springframework.core.io.Resource file=new org.springframework.core.io.FileSystemResource(settlement.getReceiptPath());
        if(!file.exists())throw ApiException.notFound("보관 중인 영수증이 없습니다.");
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
            .contentType(org.springframework.http.MediaType.parseMediaType(settlement.getReceiptContentType()==null?"application/octet-stream":settlement.getReceiptContentType())).body(file);
    }
    @GetMapping("/audits") public List<AdminAudit> audits(HttpSession s){return admin.auditLog(actor(s));}
}
