package com.campustaximate.server.repository;

import com.campustaximate.server.domain.PartyAssignment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PartyAssignmentRepository extends JpaRepository<PartyAssignment, Long> {
    boolean existsByPartyIdAndMemberId(long partyId, long memberId);
    List<PartyAssignment> findByPartyId(long partyId);
    List<PartyAssignment> findByRequestIdOrderById(long requestId);
    @Query("select count(a) > 0 from PartyAssignment a, PartyAssignment b "
            + "where a.party.id = b.party.id and a.memberId = :first and b.memberId = :second")
    boolean sharedParty(long first, long second);
}
