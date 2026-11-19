package com.campustaximate.server.repository;

import com.campustaximate.server.domain.School;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolRepository extends JpaRepository<School, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from School s where s.emailDomain = :emailDomain")
    Optional<School> findByEmailDomainForUpdate(String emailDomain);

    Optional<School> findByEmailDomain(String emailDomain);
}
