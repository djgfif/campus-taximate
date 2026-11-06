package com.campustaximate.server.repository;

import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.Settlement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {
    Optional<Settlement> findByParty(Party party);

    List<Settlement> findByReceiptPathIsNotNullAndRequestedAtLessThanEqual(Instant time);
}
