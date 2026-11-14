package com.campustaximate.server.web;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.service.*;
import com.campustaximate.server.domain.CompensationEntry;
import com.campustaximate.server.repository.CompensationEntryRepository;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class BoardingCaseController {
    private final NoShowService noShows; private final PaymentLifecycleService lifecycle;
    private final CompensationEntryRepository entries;
    private long actor(HttpSession session){Object id=session.getAttribute(LoginTokenFilter.SESSION_KEY);if(!(id instanceof Long))throw ApiException.unauthorized();return (Long)id;}
    public record VoteBody(@NotNull Boolean came) {}
    public record ExplanationBody(@NotBlank @Size(max=2000) String explanation) {}
    public record SuccessionBody(@NotNull Boolean accept) {}
    @GetMapping("/boarding-cases")
    public List<NoShowService.CaseView> mine(HttpSession session){return noShows.mine(actor(session));}
    @GetMapping("/parties/{partyId}/boarding-cases")
    public List<NoShowService.CaseView> list(@PathVariable long partyId,HttpSession session){return noShows.list(actor(session),partyId);}
    @PostMapping("/parties/{partyId}/members/{memberId}/boarding-votes")
    public void vote(@PathVariable long partyId,@PathVariable long memberId,@Valid @RequestBody VoteBody body,HttpSession session){long actor=actor(session);lifecycle.beforeDepartures();noShows.vote(actor,partyId,memberId,body.came());}
    @PostMapping("/boarding-cases/{id}/explanation")
    public void explain(@PathVariable long id,@Valid @RequestBody ExplanationBody body,HttpSession session){noShows.explain(actor(session),id,body.explanation());}
    @PostMapping("/parties/{partyId}/payer-succession")
    public void succession(@PathVariable long partyId,@Valid @RequestBody SuccessionBody body,HttpSession session){noShows.succession(actor(session),partyId,body.accept());lifecycle.afterDepartures();}
    @GetMapping("/parties/{partyId}/compensation")
    public List<CompensationEntry> compensation(@PathVariable long partyId,HttpSession session){noShows.requireMember(actor(session),partyId);return entries.findByPartyIdOrderByRequestId(partyId);}
}
