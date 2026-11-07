package com.campustaximate.server.repository;

import com.campustaximate.server.domain.MailOutbox;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MailOutboxRepository extends JpaRepository<MailOutbox, Long> {

    List<MailOutbox> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderById(MailOutbox.Status status, Instant now);

    @Modifying
    @Query("delete from MailOutbox m where m.status = com.campustaximate.server.domain.MailOutbox.Status.SENT and m.nextAttemptAt < :before")
    int deleteSentBefore(Instant before);
}
