package com.campustaximate.server.repository;

import com.campustaximate.server.domain.Settlement;
import com.campustaximate.server.domain.TransferRequest;
import com.campustaximate.server.domain.TransferStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TransferRequestRepository extends JpaRepository<TransferRequest, Long> {

    @EntityGraph(attributePaths = {"sender"})
    List<TransferRequest> findBySettlementOrderById(Settlement settlement);

    @EntityGraph(attributePaths = {"sender", "settlement"})
    @Query("select t from TransferRequest t where t.status = com.campustaximate.server.domain.TransferStatus.REQUESTED "
            + "and t.remindedAt is null and t.settlement.requestedAt <= :before")
    List<TransferRequest> findDueUnsentReminders(Instant before);

    @org.springframework.data.jpa.repository.Modifying
    @Query("update TransferRequest t set t.remindedAt = :now where t.id = :id and t.remindedAt is null "
            + "and t.status = com.campustaximate.server.domain.TransferStatus.REQUESTED "
            + "and t.settlement.id in (select s.id from Settlement s where s.requestedAt <= :before)")
    int claimReminder(long id, Instant now, Instant before);

    @Query("select t.settlement.party.id from TransferRequest t where t.id = :id")
    Optional<Long> findPartyId(Long id);

    @Query("""
            select count(t) > 0 from TransferRequest t
            where t.sender.id = :memberId and t.status = :status and t.settlement.requestedAt <= :before
            """)
    boolean existsOverdue(Long memberId, TransferStatus status, Instant before);
}
