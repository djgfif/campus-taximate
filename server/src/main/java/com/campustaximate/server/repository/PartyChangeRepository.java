package com.campustaximate.server.repository;
import com.campustaximate.server.domain.PartyChange;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface PartyChangeRepository extends JpaRepository<PartyChange,Long> {
    List<PartyChange> findByPartyIdOrderByGroupVersion(Long partyId);
}
