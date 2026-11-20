package com.campustaximate.server.service;

import com.campustaximate.matching.*;
import com.campustaximate.settlement.SettlementCalculator;
import java.nio.file.*;
import java.io.*;
import java.time.Instant;
import java.util.*;

/** Experiment-only capture and counterfactual cash ledger. Never moves actual money. */
public final class C10Prepay {
    static PrintWriter ledger, diagnostics;
    static int capRounds, capAnchors, eligibleAnchors;
    static void round(List<MatchRequest> waiting, Instant slot, MatchingSettings settings) {
        var eligible=waiting.stream().filter(r->TripPlan.of(r.direction(),settings).eligible(r,slot)).toList();
        boolean hit=false;
        for(var a:eligible) {
            eligibleAnchors++;
            long peers=eligible.stream().filter(r->r.poolKey().equals(a.poolKey()) && r.id()!=a.id()
                && r.memberId()!=a.memberId() && !a.avoids(r)).count();
            if(peers>settings.maxCandidates()-1) {hit=true;capAnchors++;}
        }
        if(hit)capRounds++;
    }
    static void day(String cid,int rounds) {
        diagnostics.printf(Locale.ROOT,"%s,%d,%d,%d,%d%n",cid,rounds,capRounds,capAnchors,eligibleAnchors);
        diagnostics.flush();capRounds=0;capAnchors=0;eligibleAnchors=0;
    }
    static void capture(String cid,int group,long expected,List<Long> units,int payer,List<Double> soloErrors,int errorIndex) {
        ledger.println(String.join("	",cid,""+group,""+expected,""+payer,""+errorIndex,
            String.join(";",units.stream().map(Object::toString).toList()),
            String.join(";",soloErrors.stream().map(Object::toString).toList())));
        ledger.flush();
    }
    static long prepay(long share,int percent) {
        if(share<=0 || percent<100)throw new IllegalArgumentException("invalid buffer");
        if(percent==115)return PaymentService.prepayAmount(share);
        return Math.addExact(Math.multiplyExact(share,percent),99)/100;
    }
    record Cash(List<Long> net,List<Long> upfront,List<Long> extra,List<Long> refund,long payerIncrease) {}
    static Cash settle(long bill,List<Long> units,int payer,long quote,int buffer,long waiver) {
        var expected=SettlementCalculator.shares(quote,SettlementCalculator.ratioUnits(units),payer);
        var shares=SettlementCalculator.shares(bill,SettlementCalculator.ratioUnits(units),payer);
        var net=new ArrayList<>(shares);var up=new ArrayList<Long>();var extra=new ArrayList<Long>();var refund=new ArrayList<Long>();
        long waived=0;
        for(int i=0;i<units.size();i++) {
            long p=i==payer?0:prepay(expected.get(i),buffer);
            long shortfall=i==payer?0:Math.max(0,shares.get(i)-p);
            long w=shortfall>0 && shortfall<=waiver?shortfall:0;
            waived+=w;net.set(i,net.get(i)-w);
            up.add(p);extra.add(shortfall-w);refund.add(i==payer?0:Math.max(0,p-shares.get(i)));
        }
        net.set(payer,net.get(payer)+waived);
        if(net.stream().mapToLong(Long::longValue).sum()!=bill)throw new AssertionError("bill conservation");
        for(int i=0;i<units.size();i++)if(i!=payer && up.get(i)+extra.get(i)-refund.get(i)!=net.get(i))
            throw new AssertionError("cash conservation");
        return new Cash(net,up,extra,refund,waived);
    }
    static List<Long> longs(String text) {return Arrays.stream(text.split(";")).map(Long::parseLong).toList();}
    static List<Double> doubles(String text) {return Arrays.stream(text.split(";")).map(Double::parseDouble).toList();}
    static void rules(String[] args)throws Exception {
        double[] errors=Files.readAllLines(Path.of(args[3])).stream().filter(s->!s.isBlank()).mapToDouble(Double::parseDouble).toArray();
        double mean=Arrays.stream(errors).average().orElseThrow();
        var rules=Files.readAllLines(Path.of(args[2]));
        try(var out=new PrintWriter(Files.newBufferedWriter(Path.of(args[4])))) {
            out.println("case_id,group_id,rule,spread,nonpayer,extra,refund,upfront,net,solo,payer_increase,payer_loss,conservation_errors,sensitivity_group_floor,sensitivity_solo_floor");
            for(String line:Files.readAllLines(Path.of(args[1]))) {
                var p=line.split("	");if(p[0].equals("case_id"))continue;
                long expected=Long.parseLong(p[2]);int payer=Integer.parseInt(p[3]);int ei=Integer.parseInt(p[4]);
                var units=longs(p[5]);var soloErrors=doubles(p[6]);
                for(double spread:new double[]{.5,1.,1.5}) {
                    // Preserve the C7 group draw; mean-preserving spread on that same residual.
                    double e=mean+spread*(errors[ei]-mean);
                    boolean groupFloor=e<=-1; e=Math.max(-.9999999999999999,e);
                    long bill=DetailedSimulation.actual(expected,e);
                    for(String rule:rules) {
                        var r=rule.split(",");
                        boolean sweep=p[0].startsWith("base_day") || p[0].startsWith("n50_day") || p[0].startsWith("n300_day");
                        if(!sweep && (!r[0].equals("current") || spread!=1.))continue;
                        long quote=DetailedSimulation.actual(expected,Double.parseDouble(r[2]));
                        Cash c=settle(bill,units,payer,quote,Integer.parseInt(r[1]),Long.parseLong(r[3]));
                        for(int i=0;i<units.size();i++) {
                            double se=mean+spread*(soloErrors.get(i)-mean);
                            boolean soloFloor=se<=-1;se=Math.max(-.9999999999999999,se);
                            long solo=DetailedSimulation.actual(units.get(i),se);
                            out.printf(Locale.ROOT,"%s,%s,%s,%.1f,%d,%d,%d,%d,%d,%d,%d,%d,0,%d,%d%n",p[0],p[1],r[0],spread,
                                i==payer?0:1,c.extra().get(i),c.refund().get(i),c.upfront().get(i),c.net().get(i),solo,
                                i==payer?c.payerIncrease():0,i==payer?Math.max(0,c.net().get(i)-solo):0,groupFloor?1:0,soloFloor?1:0);
                        }
                    }
                }
            }
        }
    }
    static void selfTest() {
        if(prepay(10001,115)!=PaymentService.prepayAmount(10001) || prepay(10001,120)!=12002)throw new AssertionError("buffer rounding");
        var c=settle(2400,List.of(1L,1L),1,2000,115,300);
        if(c.extra().getFirst()!=0 || c.net().getFirst()!=1150 || c.net().getLast()!=1250 || c.payerIncrease()!=50)
            throw new AssertionError("payer waiver absorption");
        var d=settle(3000,List.of(1L,1L),1,2000,115,300);
        if(d.extra().getFirst()!=350 || d.payerIncrease()!=0)throw new AssertionError("waiver boundary");
        var boundary=settle(2900,List.of(1L,1L),1,2000,115,300);
        if(boundary.payerIncrease()!=300 || boundary.extra().getFirst()!=0)throw new AssertionError("inclusive waiver boundary");
        for(int b:new int[]{100,110,115,120,125,130,140,150}) {
            var plain=settle(17231,List.of(4000L,6000L,3000L,9000L),3,14003,b,0);
            var waived=settle(17231,List.of(4000L,6000L,3000L,9000L),3,14003,b,1000);
            long transferred=0;
            for(int i=0;i<3;i++)transferred+=plain.net().get(i)-waived.net().get(i);
            if(waived.net().get(3)-plain.net().get(3)!=transferred || plain.payerIncrease()!=0)
                throw new AssertionError("multi-rider waiver conservation");
        }
        System.out.println("C10 production prepay, buffer and waiver conservation passed");
    }
    public static void main(String[] args)throws Exception {
        if(args[0].equals("--self-test")){selfTest();return;}
        if(args[0].equals("--rules")){rules(args);return;}
        try(var l=new PrintWriter(Files.newBufferedWriter(Path.of(args[6])));var d=new PrintWriter(Files.newBufferedWriter(Path.of(args[7])))) {
            ledger=l;diagnostics=d;
            ledger.println("case_id	group_id	expected_bill	payer	error_index	units	solo_errors");
            diagnostics.println("case_id,rounds,candidate_cap_rounds,candidate_cap_anchors,eligible_anchors");
            Class.forName("com.campustaximate.server.service.C10DetailedSimulation").getMethod("main",String[].class)
                .invoke(null,(Object)Arrays.copyOf(args,6));
        }
    }
}
