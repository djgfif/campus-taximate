package com.campustaximate.server.repository;

import com.campustaximate.server.domain.Hub;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface HubRepository extends JpaRepository<Hub, Long> {

    @Query("select h from Hub h join h.schools s where s.id = :schoolId order by h.id")
    List<Hub> findBySchool(Long schoolId);
}
