package com.campustaximate.server.repository;

import com.campustaximate.server.domain.*;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {
    @Query("select o.partyId from PaymentOrder o where o.id=:id")
    Optional<Long> findPartyId(long id);
    @Query("select count(o)>0 from PaymentOrder o where o.memberId=:memberId and o.purpose in (com.campustaximate.server.domain.PaymentPurpose.ADDITIONAL, com.campustaximate.server.domain.PaymentPurpose.POSTPAID, com.campustaximate.server.domain.PaymentPurpose.COMPENSATION) and o.status in (com.campustaximate.server.domain.PaymentStatus.REQUIRED, com.campustaximate.server.domain.PaymentStatus.OVERDUE) and o.deadlineAt<=:now")
    boolean hasUnpaidAdditional(long memberId, Instant now);
    Optional<PaymentOrder> findByOrderId(String orderId);
    List<PaymentOrder> findByPartyIdOrderById(Long partyId);
    List<PaymentOrder> findByMemberIdAndPartyIdInOrderById(Long memberId, Collection<Long> partyIds);
    Optional<PaymentOrder> findByPartyIdAndMemberIdAndPurpose(Long partyId, Long memberId, PaymentPurpose purpose);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select o from PaymentOrder o where o.id=:id")
    Optional<PaymentOrder> lockById(long id);
    List<PaymentOrder> findByStatusAndDeadlineAtLessThanEqual(PaymentStatus status, Instant now);
    List<PaymentOrder> findByExcludedAtIsNotNullAndApprovedAtIsNull();
    boolean existsByMemberIdAndPurposeAndStatus(Long memberId, PaymentPurpose purpose, PaymentStatus status);
    boolean existsByMemberIdAndStatusInAndPurposeIn(long memberId, Collection<PaymentStatus> statuses,
                                                    Collection<PaymentPurpose> purposes);
}
