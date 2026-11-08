package com.campustaximate.server.repository;

import com.campustaximate.server.domain.PartyMessage;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PartyMessageRepository extends JpaRepository<PartyMessage, Long> {
    @EntityGraph(attributePaths = {"member"})
    List<PartyMessage> findTop50ByPartyIdOrderByIdDesc(long partyId);

    @EntityGraph(attributePaths = {"member"})
    List<PartyMessage> findTop50ByPartyIdAndIdGreaterThanOrderByIdAsc(long partyId, long after);

    @Query("select distinct m.party.id from PartyMessage m")
    List<Long> findPartyIdsWithMessages();

    @Modifying
    @Query("delete from PartyMessage m where m.party.id = :partyId")
    void deleteForParty(long partyId);
}
