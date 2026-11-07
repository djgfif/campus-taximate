package com.campustaximate.server.repository;

import com.campustaximate.server.domain.MemberAvoid;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MemberAvoidRepository extends JpaRepository<MemberAvoid, Long> {
    boolean existsByMemberIdAndAvoidedMemberId(long memberId, long avoidedMemberId);
    List<MemberAvoid> findByMemberId(long memberId);
    void deleteByMemberIdAndAvoidedMemberId(long memberId, long avoidedMemberId);
    @Query("select a from MemberAvoid a where a.memberId in :ids or a.avoidedMemberId in :ids")
    List<MemberAvoid> findForMembers(java.util.Collection<Long> ids);
}
