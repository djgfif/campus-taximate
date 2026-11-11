package com.campustaximate.server.repository;

import com.campustaximate.server.domain.*;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;

public interface SettlementShareRepository extends JpaRepository<SettlementShare, Long> {
    List<SettlementShare> findBySettlementIdOrderByRideOrder(Long settlementId);
}
