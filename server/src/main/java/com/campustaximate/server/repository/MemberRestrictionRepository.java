package com.campustaximate.server.repository;

import com.campustaximate.server.domain.MemberRestriction;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRestrictionRepository extends JpaRepository<MemberRestriction, Long> {
    Optional<MemberRestriction> findFirstByMemberIdAndExpiresAtGreaterThanOrderByExpiresAtDesc(long memberId, Instant now);
}
