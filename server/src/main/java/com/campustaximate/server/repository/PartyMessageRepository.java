package com.campustaximate.server.repository;

import com.campustaximate.server.domain.PartyMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PartyMessageRepository extends JpaRepository<PartyMessage, Long> {
    @EntityGraph(attributePaths = {"member"})
    List<PartyMessage> findTop50ByPartyIdOrderByIdDesc(long partyId);

    @EntityGraph(attributePaths = {"member"})
    List<PartyMessage> findTop51ByPartyIdAndIdGreaterThanAndIdLessThanEqualOrderByIdAsc(long partyId, long after, long ceiling);

    @EntityGraph(attributePaths = {"member"})
    List<PartyMessage> findTop51ByPartyIdAndIdLessThanEqualOrderByIdDesc(long partyId, long ceiling);

    Optional<PartyMessage> findByPartyIdAndMemberIdAndClientMessageId(long partyId, long memberId, String clientMessageId);

    long countByPartyIdAndMemberIdAndCreatedAtGreaterThanEqual(long partyId, long memberId, java.time.Instant since);

    @Query("select coalesce(max(m.id), 0) from PartyMessage m where m.party.id = :partyId")
    long latestId(long partyId);

    @Query("select distinct m.party.id from PartyMessage m")
    List<Long> findPartyIdsWithMessages();

    @Modifying
    @Query("delete from PartyMessage m where m.party.id = :partyId")
    void deleteForParty(long partyId);
}
