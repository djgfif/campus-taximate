package com.campustaximate.server.repository;

import com.campustaximate.server.domain.School;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolRepository extends JpaRepository<School, Long> {
    Optional<School> findByEmailDomain(String emailDomain);
}
