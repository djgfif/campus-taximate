package com.campustaximate.server.repository;
import com.campustaximate.server.domain.BoardingCase;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
import java.time.Instant;
public interface BoardingCaseRepository extends JpaRepository<BoardingCase,Long> { List<BoardingCase> findByMemberIdOrderByIdDesc(Long memberId); List<BoardingCase> findByPartyIdOrderByRequestId(Long id); Optional<BoardingCase> findByPartyIdAndRequestId(Long partyId,Long requestId); List<BoardingCase> findByStatusAndDeadlineAtLessThanEqual(String status,Instant at); }
