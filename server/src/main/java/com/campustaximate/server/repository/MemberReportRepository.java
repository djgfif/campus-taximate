package com.campustaximate.server.repository;

import com.campustaximate.server.domain.MemberReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MemberReportRepository extends JpaRepository<MemberReport, Long> {
    boolean existsByReporterIdAndReportedIdAndPartyId(long reporterId, long reportedId, long partyId);
    @Query("select count(distinct r.party.id) from MemberReport r where r.reportedId = :memberId")
    long countReportedParties(long memberId);
}
