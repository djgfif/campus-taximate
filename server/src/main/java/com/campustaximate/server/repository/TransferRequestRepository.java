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

    @Query("select t.settlement.party.id from TransferRequest t where t.id = :id")
    Optional<Long> findPartyId(Long id);

    @Query("""
            select count(t) > 0 from TransferRequest t
            where t.sender.id = :memberId and t.status = :status and t.settlement.requestedAt <= :before
            """)
    boolean existsOverdue(Long memberId, TransferStatus status, Instant before);
}
