package com.campustaximate.server.repository;

import com.campustaximate.server.domain.SettlementConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementConfirmationRepository extends JpaRepository<SettlementConfirmation, Long> {
}
