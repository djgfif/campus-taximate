package com.campustaximate.server.repository;

import com.campustaximate.server.domain.*;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;

public interface PayoutLedgerRepository extends JpaRepository<PayoutLedger, Long> {
    boolean existsByPartyId(Long partyId);
    boolean existsBySettlementId(Long settlementId);
    Optional<PayoutLedger> findBySettlementId(Long settlementId);
}
