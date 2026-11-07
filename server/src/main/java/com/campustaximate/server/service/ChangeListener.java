package com.campustaximate.server.service;

import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.domain.Settlement;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** 요청, 일행, 정산이 저장되면 관계된 사람의 화면에 알린다. 서비스마다 호출을 빠뜨리지 않게 저장 시점에서 잡는다. */
@Component
public class ChangeListener {

    @Autowired
    @Lazy
    private ChangeBus bus;

    @PostPersist
    @PostUpdate
    void changed(Object entity) {
        if (entity instanceof RideRequest request) {
            bus.memberChanged(request.getMember().getId());
            if (request.getParty() != null) {
                bus.partyChanged(request.getParty().getId());
            }
        } else if (entity instanceof Party party) {
            bus.partyChanged(party.getId());
        } else if (entity instanceof Settlement settlement) {
            bus.partyChanged(settlement.getParty().getId());
        }
    }
}
