package com.campustaximate.server.web;

import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.service.PaymentMethodService;
import com.campustaximate.server.service.PaymentMethodService.PaymentMethodView;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payment-methods")
@RequiredArgsConstructor
public class PaymentMethodController {
    private final PaymentMethodService methods;

    public record RegisterBody(String provider, @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String billingKey,
                               String cardLabel) { }

    @GetMapping
    public List<PaymentMethodView> list(HttpSession session) {
        return methods.list(actor(session));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentMethodView register(HttpSession session, @RequestBody RegisterBody body) {
        return methods.register(actor(session), body.provider(), body.billingKey(), body.cardLabel());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpSession session, @PathVariable long id) {
        methods.delete(actor(session), id);
    }

    private long actor(HttpSession session) {
        if (!(session.getAttribute(LoginTokenFilter.SESSION_KEY) instanceof Long memberId)) {
            throw ApiException.unauthorized();
        }
        return memberId;
    }
}
