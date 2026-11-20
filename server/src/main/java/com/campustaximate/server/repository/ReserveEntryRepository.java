package com.campustaximate.server.repository;

import com.campustaximate.server.domain.ReserveEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReserveEntryRepository extends JpaRepository<ReserveEntry, Long> {
    boolean existsBySettlementId(Long settlementId);

    @Query("select coalesce(sum(e.amount), 0) from ReserveEntry e")
    long balance();

    List<ReserveEntry> findTop20ByOrderByIdDesc();
}
