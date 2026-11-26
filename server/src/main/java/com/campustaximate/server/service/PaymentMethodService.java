package com.campustaximate.server.service;

import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.Member;
import com.campustaximate.server.domain.PartyStatus;
import com.campustaximate.server.domain.PaymentMethod;
import com.campustaximate.server.domain.PaymentPurpose;
import com.campustaximate.server.domain.PaymentStatus;
import com.campustaximate.server.repository.MemberRepository;
import com.campustaximate.server.repository.PaymentMethodRepository;
import com.campustaximate.server.repository.PaymentOrderRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentMethodService {
    private static final String SIMULATED = "SIMULATED";
    private static final Set<String> TOSS_PROVIDERS = Set.of("TOSS", "TOSS_BILLING");
    private static final Pattern CARD_NUMBER = Pattern.compile("\\p{Nd}(?:[\\p{P}\\p{Z}\\p{Cf}\\s]*\\p{Nd}){12,}");
    private static final Pattern NON_DIGIT = Pattern.compile("\\P{Nd}");
    // 512 Base64 characters hold 384 bytes, including the 12-byte IV and 16-byte authentication tag.
    private static final int MAX_BILLING_KEY_BYTES = 356;
    private static final Set<PaymentStatus> UNPAID_STATUSES = Set.of(PaymentStatus.REQUIRED, PaymentStatus.OVERDUE);
    private static final Set<PaymentPurpose> UNPAID_PURPOSES =
            Set.of(PaymentPurpose.POSTPAID, PaymentPurpose.COMPENSATION, PaymentPurpose.ADDITIONAL);
    private static final Set<PartyStatus> ACTIVE_PARTY_STATUSES = Set.of(
            PartyStatus.PREPAYING, PartyStatus.READY, PartyStatus.PAYER_PENDING, PartyStatus.RIDE_UNCONFIRMED,
            PartyStatus.AWAITING_FARE, PartyStatus.OBJECTION_WINDOW, PartyStatus.REVIEW_PENDING);

    private final PaymentMethodRepository methods;
    private final MemberRepository members;
    private final PaymentOrderRepository orders;
    private final RideRequestRepository requests;
    private final AppClock clock;
    private final boolean simulated;

    public PaymentMethodService(PaymentMethodRepository methods, MemberRepository members,
            PaymentOrderRepository orders, RideRequestRepository requests, AppClock clock,
            @Value("${app.payment.simulated:false}") boolean simulated) {
        this.methods = methods;
        this.members = members;
        this.orders = orders;
        this.requests = requests;
        this.clock = clock;
        this.simulated = simulated;
    }

    public record PaymentMethodView(long id, String provider, String cardLabel, boolean active, Instant registeredAt) {
        private static PaymentMethodView of(PaymentMethod method) {
            return new PaymentMethodView(method.getId(), method.getProvider(), method.getCardLabel(),
                    method.isActive(), method.getRegisteredAt());
        }
    }

    @Transactional(readOnly = true)
    public List<PaymentMethodView> list(long memberId) {
        return methods.findByMemberIdAndActiveTrueAndDeletedAtIsNullOrderByRegisteredAtDescIdDesc(memberId)
                .stream().map(PaymentMethodView::of).toList();
    }

    @Transactional
    public PaymentMethodView register(long memberId, String provider, String billingKey, String cardLabel) {
        // Provider identifiers are case-sensitive and used as supplied.
        if (provider == null || !(TOSS_PROVIDERS.contains(provider) || (simulated && SIMULATED.equals(provider)))
                || billingKey == null || billingKey.isBlank()
                || billingKey.getBytes(StandardCharsets.UTF_8).length > MAX_BILLING_KEY_BYTES
                || CARD_NUMBER.matcher(billingKey).find()
                || cardLabel == null || cardLabel.isBlank() || cardLabel.length() > 100
                || cardLabel.contains(billingKey)) {
            throw ApiException.badRequest("INVALID_PAYMENT_METHOD", "제공사, 빌링키와 카드 라벨을 확인해 주세요.");
        }
        Member member = lockMember(memberId);
        PaymentMethod saved = methods.save(new PaymentMethod(member, provider, billingKey,
                maskCardNumber(cardLabel.strip()), clock.now()));
        return PaymentMethodView.of(saved);
    }

    @Transactional
    public void delete(long memberId, long id) {
        lockMember(memberId);
        PaymentMethod method = methods.findByIdAndMemberIdAndActiveTrueAndDeletedAtIsNull(id, memberId)
                .orElseThrow(() -> ApiException.notFound("등록한 결제수단을 찾을 수 없습니다."));
        if (orders.existsByMemberIdAndStatusInAndPurposeIn(memberId, UNPAID_STATUSES, UNPAID_PURPOSES)) {
            throw ApiException.conflict("PAYMENT_OVERDUE", "미납 결제가 있어 결제수단을 삭제할 수 없습니다.");
        }
        if (methods.countByMemberIdAndActiveTrueAndDeletedAtIsNull(memberId) <= 1
                && (requests.findByActiveMemberId(memberId).isPresent()
                        || !requests.findOpenByMember(memberId, ACTIVE_PARTY_STATUSES).isEmpty())) {
            throw ApiException.conflict("ACTIVE_RIDE_REQUIRES_PAYMENT_METHOD",
                    "진행 중인 탑승 요청이나 정산이 있어 마지막 결제수단을 삭제할 수 없습니다.");
        }
        method.deactivate(clock.now());
    }

    @Transactional
    public void seedDemo(long memberId) {
        if (!simulated) {
            return;
        }
        Member member = lockMember(memberId);
        if (!methods.existsByMemberIdAndProviderAndActiveTrueAndDeletedAtIsNull(memberId, SIMULATED)) {
            methods.save(new PaymentMethod(member, SIMULATED, "simulated-billing-" + memberId,
                    "신한카드 ****1234", clock.now()));
        }
    }

    private Member lockMember(long memberId) {
        return members.findByIdForUpdate(memberId).orElseThrow(ApiException::unauthorized);
    }

    private static String maskCardNumber(String value) {
        return CARD_NUMBER.matcher(value).replaceAll(match -> {
            String digits = NON_DIGIT.matcher(match.group()).replaceAll("");
            return "****" + digits.substring(digits.offsetByCodePoints(digits.length(), -4));
        });
    }
}
