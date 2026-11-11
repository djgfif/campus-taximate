package com.campustaximate.server.service;

import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import com.campustaximate.server.common.AppClock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @RequiredArgsConstructor
public class PartyChangeService {
    private final PartyMemberTermRepository terms;
    private final PartyChangeRepository changes;
    private final AppClock clock;
    private final PaymentOrderRepository orders;

    @Transactional(propagation=Propagation.MANDATORY)
    public void capture(Party party, List<RideRequest> riders, boolean migrated) {
        for (RideRequest rider:riders) {
            if (terms.findByPartyIdAndRequestId(party.getId(),rider.getId()).isPresent()) continue;
            PartyMemberTerm term=new PartyMemberTerm(); term.setPartyId(party.getId()); term.setRequestId(rider.getId());
            term.setMemberId(rider.getMember().getId()); term.setConfirmedShare(rider.getExpectedShare()==null?0:rider.getExpectedShare());
            term.setConfirmedPrepay(orders.findByPartyIdAndMemberIdAndPurpose(party.getId(),rider.getMember().getId(),PaymentPurpose.PREPAY).map(PaymentOrder::getAmount).orElse(0L));
            term.setMigratedUnknown(migrated); terms.save(term);
        }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void changed(Party party, Set<Long> leaving, String reason, Set<Long> held) {
        var at=clock.now();
        // 경로 조회 중 마감이 지나도 저장 직전의 시각으로 판정한다.
        boolean lateChange=reason.equals("CHANGE_EXIT_FREE") && !at.isBefore(party.getDepartureAt().minusSeconds(600));
        boolean lateNormal=reason.equals("FREE_CANCEL") && at.isAfter(party.freeCancelUntil());
        if(lateChange || lateNormal) { reason="LATE_CANCEL"; held=leaving; }
        party.setGroupVersion(party.getGroupVersion()+1);
        PartyChange event=new PartyChange(); event.setPartyId(party.getId()); event.setGroupVersion(party.getGroupVersion());
        event.setReason(reason); event.setChangedAt(at); changes.save(event);
        for (PartyMemberTerm term:terms.findByPartyIdOrderById(party.getId())) if(leaving.contains(term.getRequestId())&&term.getExitedAt()==null) {
            term.setExitedAt(at); term.setExitReason(reason); term.setRefundHeld(held.contains(term.getRequestId()));
        }
        if (!held.isEmpty()) { party.setAdminReviewRequired(true); party.setReviewReason("LATE_CANCELLATION_COMPENSATION"); }
    }
}
