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

    @EntityGraph(attributePaths = {"member"})
    List<PartyMessage> findTop50ByPartyIdAndIdLessThanOrderByIdDesc(long partyId, long before);

    boolean existsByPartyIdAndIdLessThan(long partyId, long before);

    java.util.Optional<PartyMessage> findByPartyIdAndMemberIdAndClientId(long partyId, long memberId, String clientId);

    long countByPartyIdAndMemberIdAndCreatedAtAfter(long partyId, long memberId, java.time.Instant after);

    @Query("select count(m) from PartyMessage m where m.party.id = :partyId and m.id > :after and (m.member is null or m.member.id <> :memberId)")
    long countUnread(long partyId, long memberId, long after);

    @Query("select m.imagePath from PartyMessage m where m.party.id = :partyId and m.imagePath is not null")
    List<String> findImagePaths(long partyId);

    @Query("select distinct m.party.id from PartyMessage m")
    List<Long> findPartyIdsWithMessages();

    @Modifying
    @Query("delete from PartyMessage m where m.party.id = :partyId")
    void deleteForParty(long partyId);
}
