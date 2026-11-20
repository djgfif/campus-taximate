package com.campustaximate.server.service;

import com.campustaximate.matching.*;
import com.campustaximate.settlement.SettlementCalculator;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

/** External exhaustive benchmark. Production classes are called, never replaced. */
public final class OptimalityExport {
    static final Instant BASE=DetailedSimulation.BASE;
    static final MatchingSettings S=DetailedSimulation.settings(15,.10);
    static final TripPlan PLAN=TripPlan.of(Direction.FROM_HUB,S);
    static String ids(List<MatchRequest> rs) {return rs.stream().map(MatchRequest::id).sorted().map(String::valueOf).collect(Collectors.joining(","));}
    static String longs(Collection<Long> rs) {return rs.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));}
    static long ceilMinute(long s) {return (s+59)/60*60;}
    static long[] window(MatchRequest r) {
        if(!r.instant())return new long[]{r.windowStart().getEpochSecond()-BASE.getEpochSecond(),r.windowEnd().getEpochSecond()-BASE.getEpochSecond()};
        long requested=r.requestedAt().getEpochSecond()-BASE.getEpochSecond();
        long prep=Math.max(S.instantPrepMinutes()*60L,r.walkSeconds())+S.instantDispatchMinutes()*60L;
        return new long[]{ceilMinute(requested)+prep,(requested+S.instantMatchMinutes()*60L)/60*60+prep};
    }
    static final class Day {
        final ExperimentRunner.Case c; final DetailedSimulation.Config cfg;
        final ExperimentRunner.Model model; final List<MatchRequest> all;
        final Map<Long,Long> fares=new HashMap<>(); final Map<Long,Integer> expiry=new HashMap<>();
        final Map<String,String> stats=new LinkedHashMap<>(); final Set<String> hindsight=new HashSet<>();
        final PrintWriter out; final LegProvider legs; final double[] errors;
        long parity=0,enumerated=0; double enumMs=0,prodMs=0;
        Day(ExperimentRunner.Case c,DetailedSimulation.Config cfg,Map<ExperimentRunner.Pair,RoadLeg> matrix,double[] errors,PrintWriter out) {
            this.c=c;this.cfg=cfg;this.out=out;this.errors=errors;model=new ExperimentRunner.Model(c,matrix);
            all=c.requests.stream().map(r->DetailedSimulation.request(c,r,cfg,S)).toList();
            c.requests.forEach(r->fares.put(r.id(),r.fare()));
            legs=(a,b)->Optional.of(model.between(a.own(),b.own()));
            for(var r:all){
                int sec=(int)ceilMinute(r.requestedAt().getEpochSecond()-BASE.getEpochSecond());
                while(!PLAN.expired(r,BASE.plusSeconds(sec)))sec+=60;
                expiry.put(r.id(),sec);
            }
        }
        RouteGroupVerifier verifier(Instant slot){return new RouteGroupVerifier(model,new HashMap<>(),slot,null,fares,Map.of(),slot,S);}
        String stat(Candidate candidate,RouteGroupVerifier.VerifiedRoutes route){
            String key=ids(candidate.order());
            if(stats.containsKey(key))return key;
            var units=route.solo().stream().map(r->r.fare()).toList();int payer=units.size()-1;
            var expected=SettlementCalculator.shares(route.total(),SettlementCalculator.ratioUnits(units),payer);
            long seed=DetailedSimulation.groupSeed(cfg.seed(),candidate.sortedIds());
            double error=errors[new Random(seed).nextInt(errors.length)];
            long bill=DetailedSimulation.actual(route.total(),error);
            var actual=SettlementCalculator.shares(bill,SettlementCalculator.ratioUnits(units),payer);
            DetailedSimulation.conserve(expected,route.total());DetailedSimulation.conserve(actual,bill);
            long soloActual=0;double saving=0,delay=0,expectedRatio=0;
            for(int i=0;i<units.size();i++){
                var r=candidate.order().get(i);
                long solo=DetailedSimulation.actual(units.get(i),cfg.flags().get(r.id()).soloError());
                soloActual+=solo;saving+=1-actual.get(i)/(double)solo;
                expectedRatio+=1-expected.get(i)/(double)units.get(i);
                delay+=Math.max(0,(r.walkSeconds()+route.rideSeconds().get(i)-route.solo().get(i).seconds())/60.);
            }
            long soloExpected=units.stream().mapToLong(Long::longValue).sum();
            String value=String.join("\t",key,String.valueOf(candidate.size()),String.valueOf(Math.round(candidate.savedKm()*1000)),
                String.valueOf(soloExpected-route.total()),String.valueOf(soloActual-bill),String.valueOf(saving),String.valueOf(delay),
                String.valueOf(expectedRatio),String.valueOf(route.total()),String.valueOf(bill),String.valueOf(soloActual));
            stats.put(key,value);out.println("G\t"+value);return key;
        }
        void combinations(List<MatchRequest> pool,int start,List<MatchRequest> picked,Instant slot,RouteGroupVerifier verifier,List<String> valid,boolean offline,long from,long to) {
            for(int i=start;i<pool.size();i++){
                var r=pool.get(i);
                if(!picked.isEmpty() && (!r.poolKey().equals(picked.getFirst().poolKey()) || picked.stream().anyMatch(p->p.memberId()==r.memberId()||p.avoids(r))))continue;
                long lo=from,hi=to;
                if(offline){var w=window(r);lo=Math.max(lo,w[0]);hi=Math.min(hi,w[1]);if(lo>hi)continue;}
                picked.add(r);
                if(picked.size()>=2 && (offline||PLAN.confirmable(picked,slot,S.maxGroupSize()))){
                    enumerated++;
                    var maybe=new EstimateCalculator(S).evaluate(picked,legs,offline?null:slot);
                    if(maybe.isPresent()){
                        Candidate candidate=maybe.get();
                        if(offline)candidate=new Candidate(candidate.order(),candidate.totalKm(),BASE.plusSeconds(lo),candidate.rides());
                        var verify=offline?verifier.fixedDeparture(BASE.plusSeconds(lo)):verifier;
                        if(verify.verify(candidate)==Verdict.PASS){
                            parity++;String key=stat(candidate,verify.routesOf(candidate.order()));valid.add(key);
                            if(offline)hindsight.add(key);
                        }
                    }
                }
                if(picked.size()<S.maxGroupSize())combinations(pool,i+1,picked,slot,verifier,valid,offline,lo,hi);
                picked.removeLast();
            }
        }
        void export(){
            out.println("D\t"+c.id+"\t"+all.size()+"\t"+cfg.seed());
            for(var r:all)out.println("I\t"+r.id()+"\t"+(r.requestedAt().getEpochSecond()-BASE.getEpochSecond())+"\t"+expiry.get(r.id())+"\t"+window(r)[0]+"\t"+window(r)[1]);
            int end=expiry.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            for(int sec=0;sec<=end;sec+=60){
                final int second=sec;Instant slot=BASE.plusSeconds(sec);
                var pool=all.stream().filter(r->PLAN.eligible(r,slot)&&second<=expiry.get(r.id())).toList();
                if(pool.isEmpty())continue;
                long start=System.nanoTime();List<String> valid=new ArrayList<>();
                combinations(pool,0,new ArrayList<>(),slot,verifier(slot),valid,false,Long.MIN_VALUE,Long.MAX_VALUE);
                enumMs+=(System.nanoTime()-start)/1e6;
                out.println("R\t"+sec+"\t"+longs(pool.stream().filter(r->PLAN.lastChance(r,slot)).map(MatchRequest::id).toList())+"\t"+
                    longs(pool.stream().filter(r->PLAN.expired(r,slot)).map(MatchRequest::id).toList())+"\t"+String.join(";",valid));
            }
            long start=System.nanoTime();List<String> valid=new ArrayList<>();
            combinations(all,0,new ArrayList<>(),BASE,verifier(BASE),valid,true,Long.MIN_VALUE,Long.MAX_VALUE);
            enumMs+=(System.nanoTime()-start)/1e6;
            out.println("H\t"+String.join(";",new TreeSet<>(hindsight)));
            // Independent replay of precisely the C7 production loop.
            Set<Long> finished=new HashSet<>();Map<RouteGroupVerifier.RejectionKey,Instant> rejected=new HashMap<>();
            for(int sec=0;sec<=end;sec+=60){
                Instant slot=BASE.plusSeconds(sec);var waiting=all.stream().filter(r->!finished.contains(r.id())&&!r.requestedAt().isAfter(slot)).toList();
                if(waiting.isEmpty())continue;
                var verifier=new RouteGroupVerifier(model,rejected,slot,null,fares,Map.of(),slot,S);
                start=System.nanoTime();var result=new MatchingRound(S).run(slot,waiting,legs,verifier);prodMs+=(System.nanoTime()-start)/1e6;
                for(var g:result.confirmed()){
                    var candidate=new EstimateCalculator(S).evaluate(g.order(),legs,slot).orElseThrow();
                    String key=stat(candidate,verifier.routesOf(g.order()));out.println("P\t"+sec+"\t"+key);
                    if(!hindsight.contains(key))throw new AssertionError("hindsight fails to contain production group "+key);
                    for(var r:g.order())if(!finished.add(r.id()))throw new AssertionError("duplicate production rider");
                }
                result.expired().forEach(r->finished.add(r.id()));
            }
            if(finished.size()!=all.size())throw new AssertionError("request conservation");
            out.println("T\t"+enumMs+"\t"+prodMs+"\t"+enumerated+"\t"+parity);
            out.println("END");out.flush();System.err.println("C8 "+c.id+" groups="+stats.size()+" checks="+parity);
        }
    }
    public static void main(String[] args)throws Exception {
        var cases=ExperimentRunner.readCases(Path.of(args[0]));var configs=DetailedSimulation.configs(Path.of(args[0]));
        var matrix=C7RoadMatrix.read(Path.of(args[1]));
        double[] errors;try(var lines=Files.lines(Path.of(args[2]))){errors=lines.filter(x->!x.isBlank()).mapToDouble(Double::parseDouble).toArray();}
        try(var out=new PrintWriter(Files.newBufferedWriter(Path.of(args[3])))){
            for(var c:cases)new Day(c,configs.get(c.id),matrix,errors,out).export();
        }
    }
}
