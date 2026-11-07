package com.campustaximate.server.repository;

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

    @EntityGraph(attributePaths = {"member", "hub", "school"})
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

    @Query("select r.member.id from RideRequest r where r.party.id = :partyId")
    List<Long> findMemberIdsByPartyId(long partyId);
}
