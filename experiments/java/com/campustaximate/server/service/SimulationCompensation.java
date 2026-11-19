package com.campustaximate.server.service;

import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BiFunction;

/** In-memory persistence ports only. All liability selection, credits and charges run in production apply(). */
final class SimulationCompensation {
    record Result(List<Long> net, List<Long> credits, List<Long> charges, int liable) {}
    @SuppressWarnings("unchecked")
    private static <T> T repository(Class<T> type, BiFunction<String,Object[],Object> handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)-> {
            if(method.getDeclaringClass()==Object.class) return switch(method.getName()) {
                case "toString" -> "C7 in-memory "+type.getSimpleName();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy==args[0];
                default -> throw new UnsupportedOperationException(method.toString());
            };
            return handler.apply(method.getName(),args);
        });
    }
    static Result apply(List<Long> expected, List<Integer> present, List<Long> raw, List<Long> ids, int originalPayer) {
        int n=expected.size();
        if(n!=ids.size() || raw.size()!=present.size())throw new IllegalArgumentException("compensation input shape");
        if(present.isEmpty())return new Result(List.of(),List.of(),Collections.nCopies(n,0L),0); // cancelled, no operated bill
        Party party=new Party();party.setId(1L);party.setOriginalPayerId(ids.get(originalPayer));
        Settlement settlement=new Settlement();settlement.setId(1L);settlement.setParty(party);
        List<PartyMemberTerm> terms=new ArrayList<>();List<BoardingCase> cases=new ArrayList<>();
        for(int i=0;i<n;i++) {
            PartyMemberTerm term=new PartyMemberTerm();term.setId((long)i+1);term.setPartyId(1L);
            term.setRequestId(ids.get(i));term.setMemberId(ids.get(i));term.setConfirmedShare(expected.get(i));
            term.setRefundHeld(true);terms.add(term);
            if(!present.contains(i)) {
                BoardingCase c=new BoardingCase();c.setPartyId(1L);c.setRequestId(ids.get(i));c.setMemberId(ids.get(i));
                c.setStatus("CONFIRMED");c.setKind(i==originalPayer?"ORIGINAL_PAYER":"NO_SHOW");
                // NoShowService.open() sets this production exception for the original payer.
                c.setNoCompensation(i==originalPayer);cases.add(c);
            }
        }
        cases.sort(Comparator.comparing(BoardingCase::getRequestId));
        List<SettlementShare> shares=new ArrayList<>();
        for(int j=0;j<present.size();j++) {
            SettlementShare share=new SettlementShare();share.setMemberId(ids.get(present.get(j)));
            share.setAmount(raw.get(j));share.setPayer(j==present.size()-1);shares.add(share);
        }
        List<CompensationEntry> entries=new ArrayList<>();
        var tr=repository(PartyMemberTermRepository.class,(method,args)-> {
            if(method.equals("findByPartyIdOrderById"))return terms;
            throw new UnsupportedOperationException(method);
        });
        var cr=repository(BoardingCaseRepository.class,(method,args)-> {
            if(method.equals("findByPartyIdOrderByRequestId"))return cases;
            throw new UnsupportedOperationException(method);
        });
        var er=repository(CompensationEntryRepository.class,(method,args)-> {
            if(method.equals("findBySettlementIdOrderByRequestId"))return entries.stream().sorted(Comparator.comparing(CompensationEntry::getRequestId)).toList();
            if(method.equals("save")) {var e=(CompensationEntry)args[0];entries.add(e);return e;}
            throw new UnsupportedOperationException(method);
        });
        CompensationService service=new CompensationService(tr,cr,er);
        service.apply(party,settlement,shares);
        List<Long> net=shares.stream().map(SettlementShare::getAmount).toList();
        List<Long> credits=shares.stream().map(SettlementShare::getCompensationCredit).toList();
        Map<Long,Long> byId=new HashMap<>();entries.forEach(e->byId.put(e.getRequestId(),e.getCharge()));
        List<Long> charges=ids.stream().map(id->byId.getOrDefault(id,0L)).toList();
        long creditTotal=credits.stream().mapToLong(Long::longValue).sum();
        long chargeTotal=charges.stream().mapToLong(Long::longValue).sum();
        if(creditTotal!=chargeTotal || net.stream().mapToLong(Long::longValue).sum()+chargeTotal!=raw.stream().mapToLong(Long::longValue).sum())
            throw new AssertionError("compensation ledger conservation");
        for(int i=0;i<n;i++)if(charges.get(i)>expected.get(i)||charges.get(i)<0||present.contains(i)&&charges.get(i)!=0
                ||i==originalPayer&&charges.get(i)!=0)throw new AssertionError("compensation liability/cap");
        for(int j=0;j<present.size();j++)if(net.get(j)<0||net.get(j)>raw.get(j))throw new AssertionError("invalid net share");
        if(terms.stream().anyMatch(PartyMemberTerm::isRefundHeld))throw new AssertionError("production refund hold not released");
        // Production ledger is idempotent; a second call must neither add entries nor apply credits again.
        service.apply(party,settlement,shares);
        if(entries.size()!=n || !net.equals(shares.stream().map(SettlementShare::getAmount).toList()))throw new AssertionError("compensation applied twice");
        return new Result(net,credits,charges,(int)cases.stream().filter(c->!c.isNoCompensation()).count());
    }
    static void selfTest() {
        // Sufficient cap: 3,000 -> 4,500 raw -> 3,000 net. Missing nonpayer covers 3,000.
        var one=apply(List.of(3000L,3000L,3000L),List.of(1,2),List.of(4500L,4500L),List.of(1L,2L,3L),2);
        if(!one.net().equals(List.of(3000L,3000L))||!one.charges().equals(List.of(3000L,0L,0L)))throw new AssertionError("single noshow full compensation");
        // Cap insufficient; two liable absentees divide the 6,000 charge by caps 2,000:4,000.
        var multiple=apply(List.of(2000L,4000L,4000L),List.of(2),List.of(12000L),List.of(30L,10L,20L),2);
        if(!multiple.net().equals(List.of(6000L))||!multiple.charges().equals(List.of(2000L,4000L,0L)))throw new AssertionError("multiple noshow caps");
        var partial=apply(List.of(2000L,4000L,4000L),List.of(2),List.of(7000L),List.of(30L,10L,20L),2);
        if(!partial.charges().equals(List.of(1000L,2000L,0L)))throw new AssertionError("multiple proportional charges");
        var payer=apply(List.of(3000L,3000L,3000L),List.of(0,1),List.of(4500L,4500L),List.of(1L,2L,3L),2);
        if(!payer.net().equals(List.of(4500L,4500L))||payer.charges().stream().anyMatch(x->x!=0))throw new AssertionError("original payer exempt");
        var noIncrease=apply(List.of(5000L,5000L,5000L),List.of(1,2),List.of(4000L,4000L),List.of(1L,2L,3L),2);
        if(noIncrease.charges().stream().anyMatch(x->x!=0))throw new AssertionError("no increase no compensation");
        var noAbsence=apply(List.of(1000L,1000L),List.of(0,1),List.of(1200L,1200L),List.of(1L,2L),1);
        if(!noAbsence.net().equals(List.of(1200L,1200L)))throw new AssertionError("fare error alone not compensated");
        var cancelled=apply(List.of(3000L,3000L),List.of(),List.of(),List.of(1L,2L),1);
        if(cancelled.charges().stream().anyMatch(x->x!=0))throw new AssertionError("cancelled group charged");
        System.out.println("C7 actual CompensationService.apply checks passed (single/multiple/cap/exemption/idempotence/conservation)");
    }
}
