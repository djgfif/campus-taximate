package com.campustaximate.server.repository;

import com.campustaximate.server.domain.CompensationEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompensationEntryRepository extends JpaRepository<CompensationEntry, Long> {
    List<CompensationEntry> findBySettlementIdOrderByRequestId(Long id);

    List<CompensationEntry> findByPartyIdOrderByRequestId(Long id);

    void deleteBySettlementId(Long settlementId);
}
