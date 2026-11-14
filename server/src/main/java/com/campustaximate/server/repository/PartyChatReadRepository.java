package com.campustaximate.server.repository;

import com.campustaximate.server.domain.PartyChatRead;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PartyChatReadRepository extends JpaRepository<PartyChatRead, Long> {
    Optional<PartyChatRead> findByPartyIdAndMemberId(long partyId, long memberId);

    List<PartyChatRead> findByPartyId(long partyId);

    @Modifying
    @Query("delete from PartyChatRead r where r.partyId = :partyId")
    void deleteForParty(long partyId);
}
