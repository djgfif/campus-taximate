package com.campustaximate.server.repository;
import com.campustaximate.server.domain.RideConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface RideConfirmationRepository extends JpaRepository<RideConfirmation,Long> {
    List<RideConfirmation> findByPartyIdOrderById(Long partyId);
    Optional<RideConfirmation> findByPartyIdAndMemberId(Long partyId,Long memberId);
}
