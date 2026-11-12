package com.campustaximate.server.web;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.domain.PartyJoin;
import com.campustaximate.server.service.PartyJoinService;
import jakarta.servlet.http.HttpSession;import jakarta.validation.Valid;import jakarta.validation.constraints.NotNull;
import java.util.List;import lombok.RequiredArgsConstructor;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class PartyJoinController {
    private final PartyJoinService joins;
    public record JoinBody(@NotNull Long requestId){}
    private long actor(HttpSession s){Object id=s.getAttribute(LoginTokenFilter.SESSION_KEY);if(!(id instanceof Long))throw ApiException.unauthorized();return (Long)id;}
    @PostMapping("/parties/{id}/joins")public PartyJoin offer(@PathVariable long id,@Valid @RequestBody JoinBody b,HttpSession s){return joins.offer(actor(s),id,b.requestId());}
    @GetMapping("/joins")public List<PartyJoin> mine(HttpSession s){return joins.mine(actor(s));}
}
