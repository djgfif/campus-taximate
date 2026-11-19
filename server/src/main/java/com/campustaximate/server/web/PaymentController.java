package com.campustaximate.server.web;

import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.domain.PaymentPurpose;
import com.campustaximate.server.service.PaymentService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService payments;
    public record OrderBody(@NotNull Long partyId, @NotNull PaymentPurpose purpose) { }
    public record ConfirmBody(@NotBlank String orderId, @NotBlank @Size(max=200) String paymentKey, @NotNull @PositiveOrZero Long amount) { }
    private long actor(HttpSession session) {
        Object id = session.getAttribute(LoginTokenFilter.SESSION_KEY);
        if (!(id instanceof Long)) throw ApiException.unauthorized();
        return (Long) id;
    }
    @PostMapping("/orders")
    public PaymentService.OrderView order(@Valid @RequestBody OrderBody body, HttpSession session) {
        return payments.order(actor(session), body.partyId(), body.purpose());
    }
    @PostMapping("/confirm")
    public PaymentService.OrderView confirm(@Valid @RequestBody ConfirmBody body, HttpSession session) {
        return payments.confirm(actor(session), body.orderId(), body.paymentKey(), body.amount());
    }
    @GetMapping("/orders/{orderId}")
    public PaymentService.OrderView get(@PathVariable String orderId, HttpSession session) { return payments.get(actor(session), orderId); }
}
