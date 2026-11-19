package com.campustaximate.server.repository;

import com.campustaximate.server.domain.FileDeleteJob;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface FileDeleteJobRepository extends JpaRepository<FileDeleteJob, Long> {
    List<FileDeleteJob> findTop40ByNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(Instant time);

    @Modifying
    @Query("update FileDeleteJob j set j.attempts = j.attempts + 1, j.nextAttemptAt = :time where j.id = :id")
    void defer(long id, Instant time);
}
