package com.campustaximate.server.repository;
import com.campustaximate.server.domain.BoardingExplanation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
import java.time.Instant;
public interface BoardingExplanationRepository extends JpaRepository<BoardingExplanation,Long> { Optional<BoardingExplanation> findByCaseId(Long id); }
