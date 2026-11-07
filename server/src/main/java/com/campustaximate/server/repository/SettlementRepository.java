package com.campustaximate.server.repository;

import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.Settlement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {
    Optional<Settlement> findByParty(Party party);

    /** 보관 기간이 지났는데 사진이나 계좌가 남은 정산. */
    @Query("""
            select s from Settlement s
            where (s.receiptPath is not null or s.bankName is not null) and s.storedAt <= :time
            """)
    List<Settlement> findExpired(Instant time);
}
