package com.campustaximate.server.repository;

import com.campustaximate.server.domain.RideReview;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideReviewRepository extends JpaRepository<RideReview, Long> {
    List<RideReview> findByTargetId(Long targetId);
    List<RideReview> findByPartyIdAndReviewerId(Long partyId, Long reviewerId);
    boolean existsByPartyIdAndReviewerIdAndTargetIdAndTag(Long partyId, Long reviewerId, Long targetId, String tag);
}
