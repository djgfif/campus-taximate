package com.campustaximate.server.repository;

import java.time.Instant;
import com.campustaximate.matching.Direction;
import com.campustaximate.server.domain.PartyStatus;
import java.util.Collection;
import org.springframework.data.jpa.repository.Query;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideRequestRepository extends JpaRepository<RideRequest, Long> {

    @Override
    @EntityGraph(attributePaths = {"points", "points.hub", "hub", "school", "member"})
    Optional<RideRequest> findById(Long id);

    @EntityGraph(attributePaths = {"member", "hub", "school", "points", "points.hub"})
    List<RideRequest> findByStatus(RequestStatus status);

    @EntityGraph(attributePaths = {"member"})
    List<RideRequest> findByPartyOrderByRideOrder(Party party);

    @EntityGraph(attributePaths = {"hub", "school"})
    Optional<RideRequest> findByActiveMemberId(Long memberId);

    Optional<RideRequest> findFirstByMemberIdAndPartyIsNotNullOrderByIdDesc(Long memberId);

    /** 이용 내역은 최근 50건만 보여 준다. */
    @EntityGraph(attributePaths = {"hub", "school", "party"})
    List<RideRequest> findTop50ByMemberIdOrderByIdDesc(Long memberId);

    @EntityGraph(attributePaths = {"hub", "school", "party"})
    Optional<RideRequest> findFirstByMemberIdOrderByIdDesc(Long memberId);

    @EntityGraph(attributePaths = {"member", "hub", "school"})
    List<RideRequest> findByStatusAndDirection(RequestStatus status, Direction direction);

    @Query("select r from RideRequest r join fetch r.party p where r.member.id = :memberId and r.noShow = false "
            + "and p.status in :statuses order by r.id desc")
    List<RideRequest> findOpenByMember(long memberId, Collection<PartyStatus> statuses);

    @Query("select r from RideRequest r join fetch r.party p join fetch r.member left join fetch r.hub h "
            + "where r.status = com.campustaximate.server.domain.RequestStatus.MATCHED and r.noShow = false and r.remindedAt is null "
            + "and p.status in (com.campustaximate.server.domain.PartyStatus.CONFIRMED, com.campustaximate.server.domain.PartyStatus.PREPAYING, com.campustaximate.server.domain.PartyStatus.READY) "
            + "and coalesce(r.boardAt, p.departureAt) > :now and coalesce(r.boardAt, p.departureAt) <= :until")
    List<RideRequest> findDueReminders(Instant now, Instant until);

    @Query("select count(a) > 0 from RideRequest a, RideRequest b "
            + "where a.party.id = b.party.id and a.member.id = :first and b.member.id = :second")
    boolean shareParty(long first, long second);

    long countByMemberIdAndNoShowTrueAndNoShowAtAfter(Long memberId, java.time.Instant since);

    @Query("select r.member.id from RideRequest r where r.party.id = :partyId")
    List<Long> findMemberIdsByPartyId(long partyId);
}
