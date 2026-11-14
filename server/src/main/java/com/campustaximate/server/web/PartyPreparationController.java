package com.campustaximate.server.web;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.service.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/parties") @RequiredArgsConstructor
public class PartyPreparationController {
    private final PartyReadinessService preparation;
    private final PartyService parties;
    public record Ready(@NotBlank String state) { }
    @PostMapping("/{id}/readiness")
    public PartyService.PartyView ready(@PathVariable long id,@Valid @RequestBody Ready body,HttpSession session) {
        long actor=actor(session);preparation.prepare(actor,id,body.state());return parties.view(actor,id);
    }
    @PostMapping("/{id}/call")
    public PartyService.PartyView call(@PathVariable long id,HttpSession session) {
        long actor=actor(session);preparation.call(actor,id);return parties.view(actor,id);
    }
    private long actor(HttpSession session) {if(session.getAttribute(LoginTokenFilter.SESSION_KEY) instanceof Long id)return id;throw ApiException.unauthorized();}
}
