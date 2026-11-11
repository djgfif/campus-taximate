package com.campustaximate.server.repository;
import com.campustaximate.server.domain.PartyMemberTerm;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface PartyMemberTermRepository extends JpaRepository<PartyMemberTerm,Long> {
    List<PartyMemberTerm> findByPartyIdOrderById(Long partyId);
    Optional<PartyMemberTerm> findByPartyIdAndRequestId(Long partyId,Long requestId);
    Optional<PartyMemberTerm> findByPartyIdAndMemberId(Long partyId,Long memberId);
}
