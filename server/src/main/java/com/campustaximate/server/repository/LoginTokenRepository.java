package com.campustaximate.server.repository;

import com.campustaximate.server.domain.LoginToken;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LoginTokenRepository extends JpaRepository<LoginToken, Long> {

    @Query("select t.member.id from LoginToken t where t.tokenHash = :hash and t.expiresAt > :now")
    Optional<Long> findMemberId(String hash, Instant now);

    @Modifying
    @Query("delete from LoginToken t where t.tokenHash = :hash")
    void deleteByHash(String hash);

    @Modifying
    @Query("delete from LoginToken t where t.member.id = :memberId")
    void deleteByMember(Long memberId);

    @Modifying
    @Query("delete from LoginToken t where t.expiresAt <= :now")
    void deleteExpired(Instant now);
}
