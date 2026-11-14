package com.campustaximate.server.repository;

import com.campustaximate.server.domain.*;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;

public interface FareObjectionRepository extends JpaRepository<FareObjection, Long> {
    boolean existsBySettlementId(Long settlementId);
    boolean existsBySettlementIdAndResolvedAtIsNull(Long settlementId);
    List<FareObjection> findBySettlementIdOrderById(Long settlementId);
    boolean existsBySettlementIdAndMemberId(Long settlementId, Long memberId);
}
