package com.campustaximate.server.repository;
import com.campustaximate.server.domain.PartyJoinMember;import org.springframework.data.jpa.repository.JpaRepository;import java.util.*;
public interface PartyJoinMemberRepository extends JpaRepository<PartyJoinMember,Long>{List<PartyJoinMember> findByJoinIdOrderByRideOrder(Long joinId);}
