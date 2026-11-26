package com.campustaximate.server.repository;

import com.campustaximate.server.domain.PaymentMethod;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Long> {
    boolean existsByMemberIdAndActiveTrueAndDeletedAtIsNull(long memberId);
    long countByMemberIdAndActiveTrueAndDeletedAtIsNull(long memberId);
    boolean existsByMemberIdAndProviderAndActiveTrueAndDeletedAtIsNull(long memberId, String provider);
    List<PaymentMethod> findByMemberIdAndActiveTrueAndDeletedAtIsNullOrderByRegisteredAtDescIdDesc(long memberId);
    Optional<PaymentMethod> findByIdAndMemberIdAndActiveTrueAndDeletedAtIsNull(long id, long memberId);
}
