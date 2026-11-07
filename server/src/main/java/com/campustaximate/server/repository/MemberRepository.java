package com.campustaximate.server.repository;

import com.campustaximate.server.domain.Member;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from Member m where m.id = :id")
    Optional<Member> findByIdForUpdate(long id);

    Optional<Member> findByEmail(String email);

    @EntityGraph(attributePaths = {"school"})
    Optional<Member> findWithSchoolById(Long id);
}
