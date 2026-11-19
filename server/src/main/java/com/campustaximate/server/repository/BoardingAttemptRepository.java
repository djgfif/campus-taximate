package com.campustaximate.server.repository;
import com.campustaximate.server.domain.BoardingAttempt;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface BoardingAttemptRepository extends JpaRepository<BoardingAttempt,Long> {
 Optional<BoardingAttempt> findByPartyIdAndMemberIdAndGeneration(long partyId,long memberId,int generation);
}
