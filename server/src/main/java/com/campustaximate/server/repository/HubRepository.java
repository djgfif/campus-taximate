package com.campustaximate.server.repository;

import com.campustaximate.server.domain.Hub;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface HubRepository extends JpaRepository<Hub, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hub h where h.id=:id")
    java.util.Optional<Hub> findByIdForUpdate(long id);

    @Query("select h from Hub h join h.schools s where s.id = :schoolId and h.active = true and h.fieldChecked = true order by h.id")
    List<Hub> findBySchool(Long schoolId);
}
