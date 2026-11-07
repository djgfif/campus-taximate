package com.campustaximate.server.repository;

import com.campustaximate.server.domain.Member;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByEmail(String email);

    @EntityGraph(attributePaths = {"school"})
    Optional<Member> findWithSchoolById(Long id);
}
