package com.campustaximate.server.service;

import com.campustaximate.matching.*;
import com.campustaximate.settlement.SettlementCalculator;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Independent replay of groups actually chosen by the Python exact solver. */
public final class OptimalityParity {
    public static void main(String[] args)throws Exception {
        var cases=ExperimentRunner.readCases(Path.of(args[0]));var configs=DetailedSimulation.configs(Path.of(args[0]));
        var matrix=C7RoadMatrix.read(Path.of(args[1]));var by=new HashMap<String,OptimalityExport.Day>();
        try(var sink=new PrintWriter(Writer.nullWriter())){
            for(var c:cases)by.put(c.id,new OptimalityExport.Day(c,configs.get(c.id),matrix,new double[]{0},sink));
        }
        int groups=0,riders=0;var policies=new TreeMap<String,Integer>();var used=new HashMap<String,Set<Long>>();
        for(String line:Files.readAllLines(Path.of(args[2]))){
            if(line.isBlank())continue;
            var p=line.split("\t");var day=by.get(p[0]);String policy=p[1];boolean hindsight=policy.equals("HINDSIGHT");
            var ids=Arrays.stream(p[3].split(",")).map(Long::valueOf).toList();
            var group=day.all.stream().filter(r->ids.contains(r.id())).toList();
            if(group.size()!=ids.size()||group.size()<2||group.size()>4)throw new AssertionError("size");
            var seen=used.computeIfAbsent(p[0]+policy,x->new HashSet<>());
            for(var r:group){if(!seen.add(r.id()))throw new AssertionError("duplicate rider");
                if(!r.poolKey().equals(group.getFirst().poolKey()))throw new AssertionError("pool");}
            Instant slot=hindsight?OptimalityExport.BASE:OptimalityExport.BASE.plusSeconds(Integer.parseInt(p[2]));
            long lo=Long.MIN_VALUE,hi=Long.MAX_VALUE;
            if(hindsight){for(var r:group){var w=OptimalityExport.window(r);lo=Math.max(lo,w[0]);hi=Math.min(hi,w[1]);}
                if(lo>hi)throw new AssertionError("no common departure");}
            else{
                for(var r:group)if(!OptimalityExport.PLAN.eligible(r,slot))throw new AssertionError("ineligible");
                if(!OptimalityExport.PLAN.confirmable(group,slot,4))throw new AssertionError("not confirmable");
            }
            var candidate=new EstimateCalculator(OptimalityExport.S).evaluate(group,day.legs,hindsight?null:slot).orElseThrow();
            if(hindsight)candidate=new Candidate(candidate.order(),candidate.totalKm(),OptimalityExport.BASE.plusSeconds(lo),candidate.rides());
            var verifier=day.verifier(slot);if(hindsight)verifier.fixedDeparture(OptimalityExport.BASE.plusSeconds(lo));
            if(verifier.verify(candidate)!=Verdict.PASS)throw new AssertionError("production feasibility parity");
            var route=verifier.routesOf(candidate.order());var units=route.solo().stream().map(r->r.fare()).toList();
            var shares=SettlementCalculator.shares(route.total(),SettlementCalculator.ratioUnits(units),units.size()-1);
            DetailedSimulation.conserve(shares,route.total());groups++;riders+=group.size();policies.merge(policy,1,Integer::sum);
        }
        try(var out=new PrintWriter(Files.newBufferedWriter(Path.of(args[3])))){
            out.print("{\"status\":\"PASS\",\"groups\":"+groups+",\"riders\":"+riders+",\"conservation_errors\":0,\"by_policy\":{");
            boolean first=true;for(var entry:policies.entrySet()){
                if(!first)out.print(",");first=false;out.print("\""+entry.getKey()+"\":"+entry.getValue());}
            out.println("}}");
        }
        System.out.println("Production verifier replay passed: "+groups+" groups, "+riders+" riders");
    }
}
