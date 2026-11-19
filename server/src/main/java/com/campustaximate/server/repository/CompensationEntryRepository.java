package com.campustaximate.server.repository;
import com.campustaximate.server.domain.CompensationEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
import java.time.Instant;
public interface CompensationEntryRepository extends JpaRepository<CompensationEntry,Long> { List<CompensationEntry> findBySettlementIdOrderByRequestId(Long id); List<CompensationEntry> findByPartyIdOrderByRequestId(Long id); }
