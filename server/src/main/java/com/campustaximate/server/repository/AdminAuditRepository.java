package com.campustaximate.server.repository;
import com.campustaximate.server.domain.AdminAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
import java.time.Instant;
public interface AdminAuditRepository extends JpaRepository<AdminAudit,Long> { Optional<AdminAudit> findByOperationKey(String key); }
