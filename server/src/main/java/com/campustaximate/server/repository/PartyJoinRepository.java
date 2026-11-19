package com.campustaximate.server.repository;
import com.campustaximate.server.domain.PartyJoin;import org.springframework.data.jpa.repository.JpaRepository;import java.util.*;
public interface PartyJoinRepository extends JpaRepository<PartyJoin,Long>{
    Optional<PartyJoin> findByPaymentOrderId(Long orderId);List<PartyJoin> findByStateOrderById(String state);
    List<PartyJoin> findByMemberIdOrderByIdDesc(Long memberId);
}
