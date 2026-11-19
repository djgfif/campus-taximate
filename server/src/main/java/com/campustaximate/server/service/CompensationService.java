package com.campustaximate.server.service;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import com.campustaximate.settlement.SettlementCalculator;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 금융 처리를 만들기 직전에 확정 스냅샷과 실제 몫으로 보전 장부를 확정한다. */
@Service @RequiredArgsConstructor
public class CompensationService {
    private final PartyMemberTermRepository terms; private final BoardingCaseRepository cases;
    private final CompensationEntryRepository entries;
    @Transactional(propagation=Propagation.MANDATORY)
    public void apply(Party party,Settlement settlement,List<SettlementShare> shares) {
        if(!entries.findBySettlementIdOrderByRequestId(settlement.getId()).isEmpty()) return;
        var original=terms.findByPartyIdOrderById(party.getId());
        var liable=cases.findByPartyIdOrderByRequestId(party.getId()).stream().filter(c->"CONFIRMED".equals(c.getStatus())&&!c.isNoCompensation()).toList();
        var caps=liable.stream().map(c->original.stream().filter(t->t.getRequestId().equals(c.getRequestId())).findFirst().orElseThrow().getConfirmedShare()).toList();
        var increases=shares.stream().map(s->Math.max(0,s.getAmount()-original.stream().filter(t->t.getMemberId().equals(s.getMemberId())).findFirst().orElseThrow().getConfirmedShare())).toList();
        long cap=caps.stream().mapToLong(Long::longValue).sum();
        var credits=SettlementCalculator.compensation(increases,cap); long total=credits.stream().mapToLong(Long::longValue).sum();
        var charges=liable.isEmpty()?List.<Long>of():SettlementCalculator.compensationBurdens(total,caps,liable.stream().map(BoardingCase::getRequestId).toList());
        for(int i=0;i<shares.size();i++) {
            var s=shares.get(i);var term=original.stream().filter(t->t.getMemberId().equals(s.getMemberId())).findFirst().orElseThrow();
            s.setRawAmount(s.getAmount());s.setCompensationCredit(credits.get(i));s.setAmount(s.getAmount()-credits.get(i));
            save(party,settlement,term,s.getRawAmount(),credits.get(i),0,s.getAmount());
        }
        for(var term:original) {
            if(shares.stream().anyMatch(s->s.getMemberId().equals(term.getMemberId()))) continue;
            int index=java.util.stream.IntStream.range(0,liable.size()).filter(i->liable.get(i).getRequestId().equals(term.getRequestId())).findFirst().orElse(-1);
            long charge=index<0?0:charges.get(index);save(party,settlement,term,0,0,charge,charge);
        }
        // 판정이 모두 끝난 뒤 금융 처리와 같은 트랜잭션에서 보류를 해제한다.
        original.forEach(t->t.setRefundHeld(false));
    }
    private void save(Party party,Settlement settlement,PartyMemberTerm term,long raw,long credit,long charge,long net) {
        CompensationEntry e=new CompensationEntry();e.setPartyId(party.getId());e.setSettlementId(settlement.getId());e.setRequestId(term.getRequestId());e.setMemberId(term.getMemberId());
        e.setRawShare(raw);e.setCredit(credit);e.setCharge(charge);e.setNetShare(net);entries.save(e);
    }
}
