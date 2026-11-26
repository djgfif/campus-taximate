package com.campustaximate.server.repository;

import com.campustaximate.server.domain.SettlementConfirmation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SettlementConfirmationRepository extends JpaRepository<SettlementConfirmation, Long> {
    boolean existsBySettlementIdAndMemberIdAndRevision(long settlementId, long memberId, int revision);

    boolean existsBySettlementIdAndMemberId(long settlementId, long memberId);

    boolean existsBySettlementId(long settlementId);

    List<SettlementConfirmation> findBySettlementIdOrderById(long settlementId);

    List<SettlementConfirmation> findBySettlementIdAndRevisionOrderById(long settlementId, int revision);

    @Query("select c.member.id from SettlementConfirmation c where c.settlement.id = :settlementId and c.revision = :revision")
    List<Long> findConfirmedMemberIds(long settlementId, int revision);

    void deleteBySettlementId(long settlementId);

    void deleteBySettlementIdAndMemberIdAndRevision(long settlementId, long memberId, int revision);
}
