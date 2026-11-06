package com.campustaximate.server.repository;

import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideRequestRepository extends JpaRepository<RideRequest, Long> {

    @EntityGraph(attributePaths = {"member", "hub"})
    List<RideRequest> findByStatus(RequestStatus status);

    @EntityGraph(attributePaths = {"member"})
    List<RideRequest> findByPartyOrderByDropoffOrder(Party party);

    Optional<RideRequest> findByActiveMemberId(Long memberId);

    Optional<RideRequest> findFirstByMemberIdAndPartyIsNotNullOrderByIdDesc(Long memberId);

    @EntityGraph(attributePaths = {"hub"})
    List<RideRequest> findByMemberIdOrderByIdDesc(Long memberId);
}
