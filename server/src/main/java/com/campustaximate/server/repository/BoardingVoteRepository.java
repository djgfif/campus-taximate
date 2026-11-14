package com.campustaximate.server.repository;
import com.campustaximate.server.domain.BoardingVote;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
import java.time.Instant;
public interface BoardingVoteRepository extends JpaRepository<BoardingVote,Long> { List<BoardingVote> findByCaseIdOrderById(Long id); Optional<BoardingVote> findByCaseIdAndActorId(Long caseId,Long actorId); }
