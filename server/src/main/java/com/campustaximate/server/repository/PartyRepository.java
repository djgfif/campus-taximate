package com.campustaximate.server.repository;

import com.campustaximate.matching.Direction;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.PartyStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PartyRepository extends JpaRepository<Party, Long> {

    List<Party> findByStatus(PartyStatus status);
    List<Party> findByStatusIn(java.util.Collection<PartyStatus> statuses);

    /** 금액 수정, 보냄, 취소처럼 일행을 바꾸는 작업을 한 줄로 세운다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Party p where p.id = :id")
    Optional<Party> findByIdForUpdate(Long id);

    List<Party> findByDirectionAndStatusAndDepartureAtLessThanEqual(Direction direction, PartyStatus status, Instant time);
}
