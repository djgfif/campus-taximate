package com.campustaximate.server.web;

import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.service.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/parties") @RequiredArgsConstructor
public class PartyDecisionController {
    private final RideConfirmationService confirmations;
    private final RideRequestService requests;
    private final PartyService parties;
    private final PaymentService payments;
    public record Answer(@NotNull Boolean rode) { }
    public record Leave(@Min(1) long groupVersion) { }
    public record ExitResult(boolean freeRefund,String message) { }
    @PostMapping("/{id}/ride-confirmation")
    public PartyService.PartyView answer(@PathVariable long id,@Valid @RequestBody Answer body,HttpSession session) {
        long actor=actor(session); confirmations.answer(actor,id,body.rode()); payments.retryDue();
        return parties.view(actor,id);
    }
    @PostMapping("/{id}/leave-after-change")
    public ExitResult leave(@PathVariable long id,@Valid @RequestBody Leave body,HttpSession session) {
        boolean free=requests.leaveAfterChange(actor(session),id,body.groupVersion()); payments.retryDue();
        return new ExitResult(free,free?"변경으로 빠졌어요. 승인된 선결제는 전액 환불해요.":"무료 취소가 아니에요. 선결제는 환불하지 않고 정산 처리를 기다려요.");
    }
    private long actor(HttpSession session) {
        if(session.getAttribute(LoginTokenFilter.SESSION_KEY) instanceof Long id) return id;
        throw ApiException.unauthorized();
    }
}
