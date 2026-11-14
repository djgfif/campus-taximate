package com.campustaximate.server.repository;

import com.campustaximate.server.domain.*;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;

public interface PaymentOperationRepository extends JpaRepository<PaymentOperation, Long> {
    @Query("select o.paymentOrderId from PaymentOperation o where o.id=:id")
    Optional<Long> findOrderId(long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select o from PaymentOperation o where o.id=:id")
    Optional<PaymentOperation> lockById(long id);
    Optional<PaymentOperation> findByIdempotencyKey(String key);
    List<PaymentOperation> findByPaymentOrderIdOrderById(Long paymentOrderId);
    List<PaymentOperation> findByStatusInAndNextAttemptAtLessThanEqualOrderById(Collection<PaymentOperationStatus> statuses, Instant now);
}
