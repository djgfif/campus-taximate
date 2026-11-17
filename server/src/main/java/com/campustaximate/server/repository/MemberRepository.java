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

    /** 로그인 코드·비밀번호 실패 횟수처럼 읽고 바로 고치는 값은 회원을 잠그고 읽는다. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from Member m where m.email = :email")
    Optional<Member> findByEmailForUpdate(String email);

    @EntityGraph(attributePaths = {"school"})
    Optional<Member> findWithSchoolById(Long id);
}
