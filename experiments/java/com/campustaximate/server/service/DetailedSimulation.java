package com.campustaximate.server.service;

import com.campustaximate.matching.*;
import com.campustaximate.server.route.RouteClient;
import com.campustaximate.settlement.SettlementCalculator;
import java.io.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;

/** C7 scheduling/measurement adapter; selection, verification, allocation and prepay are production calls. */
public final class DetailedSimulation {
    static final Instant BASE = Instant.parse("2026-11-17T03:00:00Z"); // noon KST, aligned 5-min slot
    record Flags(boolean instant, boolean paid, boolean absent, double soloError) {}
    record Config(int delay, double saving, long seed, boolean empirical, Map<Long, Flags> flags) {}
    record CachedRound(RoundResult result, Map<List<MatchRequest>, RouteGroupVerifier.VerifiedRoutes> routes,
                       double elapsed, int[] checks) {}
    record BaselineReplay(String signature, Map<Integer,CachedRound> rounds) {}
    static final Map<Long,BaselineReplay> baselines=new HashMap<>();
    static MatchingSettings settings(int delay, double saving) {
        MatchingSettings d = MatchingSettings.everyMinute();
        return new MatchingSettings(delay, saving, d.maxCandidates(), d.maxGroupSize(), d.maxVerifications(),
                d.leadMinutes(), d.slotMinutes(), d.reservationSlotMinutes(), d.earlyConfirmMinutes(), d.searchMillis(),
                d.instantMatchMinutes(), d.instantPrepMinutes(), d.instantDispatchMinutes());
    }
    static MatchRequest request(ExperimentRunner.Case c, ExperimentRunner.Input r, Config cfg, MatchingSettings s) {
        return new MatchRequest(r.id(), r.id(), Direction.FROM_HUB, 1, c.hub, Gender.MALE,
                BASE.plusSeconds((long)r.arrival() + s.leadMinutes()*60L),
                BASE.plusSeconds(r.deadline() + s.leadMinutes()*60L), null, null,
                r.own(), r.km(), r.seconds()).requested(BASE.plusSeconds((long)r.arrival()), cfg.flags().get(r.id()).instant());
    }
    static long actual(long expected, double error) {
        if (!Double.isFinite(error) || error <= -1) throw new IllegalArgumentException("nonpositive actual fare");
        long value = Math.max(1, Math.round(expected*(1+error)));
        if (value > 10_000_000) throw new IllegalArgumentException("fare outside production limit");
        return value;
    }
    static long sum(List<Long> values) { return values.stream().mapToLong(Long::longValue).sum(); }
    static long groupSeed(long daySeed,List<Long> sortedIds) {
        // A polynomial ID hash collides for pairs such as [1,64] and [2,33].
        // Hash the unambiguous whole key so distinct groups do not share error streams by construction.
        try {
            byte[] bytes=(daySeed+":"+sortedIds).getBytes(StandardCharsets.UTF_8);
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(bytes)).getLong();
        } catch(NoSuchAlgorithmException e) {throw new AssertionError(e);}
    }
    static void conserve(List<Long> values, long total) {
        if (sum(values)!=total) throw new AssertionError("settlement conservation");
    }
    static void run(ExperimentRunner.Case c, Config cfg, Map<ExperimentRunner.Pair, RoadLeg> matrix,
                    double[] errors, PrintWriter riders, PrintWriter groups, PrintWriter days) {
        MatchingSettings s=settings(cfg.delay(),cfg.saving());
        var ordered=c.requests.stream().sorted(Comparator.comparingDouble(ExperimentRunner.Input::arrival)
                .thenComparingLong(ExperimentRunner.Input::id)).toList();
        Map<Long, ExperimentRunner.Input> inputs=new HashMap<>(); c.requests.forEach(r->inputs.put(r.id(),r));
        Map<Long, Long> fares=new HashMap<>(); c.requests.forEach(r->fares.put(r.id(),r.fare()));
        ExperimentRunner.Model model=new ExperimentRunner.Model(c,matrix);
        List<MatchRequest> waiting=new ArrayList<>(); Set<Long> finished=new HashSet<>();
        Map<RouteGroupVerifier.RejectionKey, Instant> rejected=new HashMap<>();
        int next=0, matched=0, expired=0, unpaid=0, groupCount=0, checks=0, fail=0, unavailable=0;
        int budgetRounds=0, rounds=0; double engineMs=0, maxRoundMs=0;
        String signature=s.toString()+c.requests.toString()+ordered.stream()
                .map(r->r.id()+":"+cfg.flags().get(r.id()).instant()+":"+cfg.flags().get(r.id()).paid()).toList();
        BaselineReplay replay=baselines.get(cfg.seed());
        boolean reuse=replay!=null&&replay.signature().equals(signature);
        boolean save=c.id.startsWith("base_day");
        Map<Integer,CachedRound> recorded=new HashMap<>();
        int end=ordered.stream().mapToInt(ExperimentRunner.Input::deadline).max().orElse(0)+600;
        for(int second=0;second<=end && (next<ordered.size()||!waiting.isEmpty());second+=60) {
            while(next<ordered.size() && ordered.get(next).arrival()<=second) {
                var r=ordered.get(next++);
                if(cfg.flags().get(r.id()).paid()) waiting.add(request(c,r,cfg,s));
                else { unpaid++; if(!finished.add(r.id()))throw new AssertionError("duplicate unpaid");
                    writeUnmatched(riders,c.id,r,cfg.flags().get(r.id()),"unpaid"); }
            }
            if(waiting.isEmpty())continue;
            Instant slot=BASE.plusSeconds(second);
            RouteGroupVerifier verifier=new RouteGroupVerifier(model,rejected,slot,null,fares,Map.of(),slot,s);
            int[] v=new int[3];
            GroupVerifier measured=new GroupVerifier() {
                public Verdict verify(Candidate candidate){return check(candidate).verdict();}
                public Verification check(Candidate candidate){var r=verifier.check(candidate);
                    v[r.verdict()==Verdict.PASS?0:r.verdict()==Verdict.FAIL?1:2]++;return r;}
                public boolean recentlyRejected(Candidate candidate){return verifier.recentlyRejected(candidate);}
            };
            LegProvider legs=(left,right)-> {try{return Optional.of(model.between(left.own(),right.own()));}
                catch(RouteClient.RouteUnavailableException e){return Optional.empty();}};
            RoundResult result; double elapsed;
            Map<List<MatchRequest>,RouteGroupVerifier.VerifiedRoutes> verified=new HashMap<>();
            if(reuse) {
                CachedRound cached=replay.rounds().get(second);
                if(cached==null)throw new AssertionError("replay round mismatch");
                result=cached.result();elapsed=cached.elapsed();System.arraycopy(cached.checks(),0,v,0,3);verified=cached.routes();
            } else {
                long start=System.nanoTime();
                result=new MatchingRound(s).run(slot,List.copyOf(waiting),legs,measured);
                elapsed=(System.nanoTime()-start)/1e6;
                for(var g:result.confirmed())verified.put(g.order(),verifier.routesOf(g.order()));
                if(save)recorded.put(second,new CachedRound(result,Map.copyOf(verified),elapsed,v.clone()));
            }
            engineMs+=elapsed;maxRoundMs=Math.max(maxRoundMs,elapsed);rounds++;
            if(elapsed>=s.searchMillis())budgetRounds++; // includes verification; conservative budget warning
            checks+=v[0];fail+=v[1];unavailable+=v[2];
            Set<Long> remove=new HashSet<>();
            for(ConfirmedGroup g:result.confirmed()) {
                var route=verified.get(g.order());
                if(route==null)throw new AssertionError("missing verified route");
                int n=g.order().size(),payer=g.order().indexOf(g.payer()); groupCount++;
                List<Long> units=route.solo().stream().map(RouteClient.Route::fare).toList();
                List<Long> expectedRatio=SettlementCalculator.shares(route.total(),SettlementCalculator.ratioUnits(units),payer);
                List<Long> expectedEqual=SettlementCalculator.shares(route.total(),Collections.nCopies(n,1L),payer);
                conserve(expectedRatio,route.total());conserve(expectedEqual,route.total());
                long errorSeed=groupSeed(cfg.seed(),g.order().stream().map(MatchRequest::id).sorted().toList());
                double e=errors[new Random(errorSeed).nextInt(errors.length)];
                long bill=actual(route.total(),cfg.empirical()?e:0);
                List<Long> fullRatio=SettlementCalculator.shares(bill,SettlementCalculator.ratioUnits(units),payer);
                List<Long> fullEqual=SettlementCalculator.shares(bill,Collections.nCopies(n,1L),payer);
                conserve(fullRatio,bill);conserve(fullEqual,bill);
                List<Integer> present=new ArrayList<>();
                for(int i=0;i<n;i++)if(!cfg.flags().get(g.order().get(i).id()).absent())present.add(i);
                List<Long> remainingUnits=present.stream().map(units::get).toList();
                List<Long> ratio=List.of(),equal=List.of();
                if(!present.isEmpty()) {
                    int remainingPayer=present.size()-1; // last present alighting rider pays
                    ratio=SettlementCalculator.shares(bill,SettlementCalculator.ratioUnits(remainingUnits),remainingPayer);
                    equal=SettlementCalculator.shares(bill,Collections.nCopies(present.size(),1L),remainingPayer);
                    conserve(ratio,bill);conserve(equal,bill);
                }
                var ids=g.order().stream().map(MatchRequest::id).toList();
                var compensatedRatio=SimulationCompensation.apply(expectedRatio,present,ratio,ids,payer);
                var compensatedEqual=SimulationCompensation.apply(expectedEqual,present,equal,ids,payer);
                groups.printf(Locale.ROOT,"%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",c.id,groupCount,n,present.size(),route.total(),bill,0,present.isEmpty()?1:0,
                    sum(compensatedRatio.credits()),sum(compensatedRatio.charges()),sum(compensatedEqual.credits()),sum(compensatedEqual.charges()),compensatedRatio.liable());
                for(int i=0;i<n;i++) {
                    MatchRequest r=g.order().get(i);var in=inputs.get(r.id());var f=cfg.flags().get(r.id());
                    if(!finished.add(r.id()))throw new AssertionError("duplicate match");
                    remove.add(r.id());matched++;
                    double delay=Math.max(0,(r.walkSeconds()+route.rideSeconds().get(i)-route.solo().get(i).seconds())/60.);
                    if(delay>cfg.delay()+1e-9)throw new AssertionError("delay threshold ignored");
                    if(route.total()/(double)sum(units)>1-cfg.saving()+1e-9)throw new AssertionError("saving threshold ignored");
                    double wait=(second-in.arrival())/60.;
                    int pi=present.indexOf(i);
                    long solo=actual(units.get(i),cfg.empirical()?f.soloError():0);
                    boolean currentNonpayer=pi>=0&&pi!=present.size()-1;
                    long pr=PaymentService.prepayAmount(expectedRatio.get(i)),pe=PaymentService.prepayAmount(expectedEqual.get(i));
                    List<String> row=new ArrayList<>(List.of(c.id,String.valueOf(r.id()),String.valueOf(12+(int)in.arrival()/3600),
                        f.absent()?"noshow":"boarded",f.instant()?"1":"0",String.valueOf(n),String.valueOf(present.size()),String.valueOf(n-present.size()),
                        String.valueOf(wait),String.valueOf(delay),String.valueOf(1-expectedRatio.get(i)/(double)units.get(i)),
                        String.valueOf(1-expectedEqual.get(i)/(double)units.get(i)),i==payer?"0":"1"));
                    financial(row,compensatedRatio.net(),compensatedEqual.net(),pi,solo,pr,pe,currentNonpayer,fullRatio.get(i),fullEqual.get(i));
                    financial(row,ratio,equal,pi,solo,pr,pe,currentNonpayer,fullRatio.get(i),fullEqual.get(i));
                    row.add(String.valueOf(pi<0?0:compensatedRatio.credits().get(pi)));
                    row.add(String.valueOf(pi<0?0:compensatedEqual.credits().get(pi)));
                    row.add(String.valueOf(compensatedRatio.charges().get(i)));
                    row.add(String.valueOf(compensatedEqual.charges().get(i)));
                    row.add(String.valueOf(expectedRatio.get(i)));row.add(String.valueOf(expectedEqual.get(i)));
                    row.add(pi<0?"":String.valueOf((compensatedRatio.net().get(pi)-expectedRatio.get(i))/(double)expectedRatio.get(i)));
                    row.add(pi<0?"":String.valueOf((compensatedEqual.net().get(pi)-expectedEqual.get(i))/(double)expectedEqual.get(i)));
                    row.add(currentNonpayer?"1":"0");
                    riders.println(String.join(",",row));
                }
            }
            for(var r:result.expired()) {
                if(!finished.add(r.id()))throw new AssertionError("duplicate expiry");
                remove.add(r.id());expired++;writeUnmatched(riders,c.id,inputs.get(r.id()),cfg.flags().get(r.id()),"expired");
            }
            waiting.removeIf(r->remove.contains(r.id()));
        }
        if(!waiting.isEmpty()||next!=ordered.size()||finished.size()!=c.requests.size()
                ||matched+expired+unpaid!=c.requests.size())throw new AssertionError("request conservation");
        if(save)baselines.put(cfg.seed(),new BaselineReplay(signature,recorded));
        days.printf(Locale.ROOT,"%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%d,%d%n",c.id,c.requests.size(),matched,
                expired,unpaid,groupCount,checks,fail,unavailable,rounds,budgetRounds,engineMs,maxRoundMs,model.calls,0);
        days.flush();riders.flush();groups.flush();
        System.err.println("C7 "+c.id+" matched="+matched+"/"+c.requests.size());
    }
    static void writeUnmatched(PrintWriter out,String id,ExperimentRunner.Input r,Flags f,String status) {
        List<String> row=new ArrayList<>(List.of(id,String.valueOf(r.id()),String.valueOf(12+(int)r.arrival()/3600),status,f.instant()?"1":"0"));
        row.addAll(Collections.nCopies(33,""));out.println(String.join(",",row));
    }
    static void financial(List<String> row,List<Long> ratio,List<Long> equal,int i,long solo,long pr,long pe,boolean nonpayer,long fullRatio,long fullEqual) {
        if(i<0){row.addAll(Collections.nCopies(8,""));return;}
        row.add(String.valueOf(1-ratio.get(i)/(double)solo));row.add(String.valueOf(1-equal.get(i)/(double)solo));
        row.add(ratio.get(i)>solo?"1":"0");row.add(equal.get(i)>solo?"1":"0");
        row.add(nonpayer&&ratio.get(i)>pr?"1":"0");row.add(nonpayer&&equal.get(i)>pe?"1":"0");
        row.add(String.valueOf((ratio.get(i)-fullRatio)/(double)fullRatio));row.add(String.valueOf((equal.get(i)-fullEqual)/(double)fullEqual));
    }
    static Map<String,Config> configs(Path input)throws IOException {
        Map<String,Config> configs=new HashMap<>();Config current=null;
        for(String line:Files.readAllLines(input)) {
            String[] p=line.split("\t");
            if(p[0].equals("CASE")) {current=new Config(Integer.parseInt(p[18]),Double.parseDouble(p[19]),Long.parseLong(p[20]),
                    p[21].equals("1"),new HashMap<>());configs.put(p[2],current);}
            if(p[0].equals("R"))current.flags().put(Long.parseLong(p[1]),new Flags(p[10].equals("1"),p[11].equals("1"),p[12].equals("1"),Double.parseDouble(p[13])));
        }return configs;
    }
    public static void main(String[] args)throws Exception {
        if(args[0].equals("--self-test")){selfTest();return;}
        Path input=Path.of(args[0]);var cfg=configs(input);var matrix=ExperimentRunner.readMatrix(Path.of(args[1]));
        double[] errors;
        try(var lines=Files.lines(Path.of(args[2]))){errors=lines.filter(x->!x.isBlank()).mapToDouble(Double::parseDouble).toArray();}
        if(errors.length==0||Arrays.stream(errors).anyMatch(e->!Double.isFinite(e)||e<=-1))throw new IllegalArgumentException("invalid C5 errors");
        try(PrintWriter riders=new PrintWriter(Files.newBufferedWriter(Path.of(args[3])));
            PrintWriter groups=new PrintWriter(Files.newBufferedWriter(Path.of(args[4])));
            PrintWriter days=new PrintWriter(Files.newBufferedWriter(Path.of(args[5])))) {
            riders.println("case_id,id,hour,status,instant,group_size,remaining_size,absent_count,wait_minutes,delay_minutes,expected_ratio_saving,expected_equal_saving,original_nonpayer,ratio_saving,equal_saving,ratio_harmed,equal_harmed,ratio_additional,equal_additional,ratio_burden_increase,equal_burden_increase,raw_ratio_saving,raw_equal_saving,raw_ratio_harmed,raw_equal_harmed,raw_ratio_additional,raw_equal_additional,raw_ratio_burden_increase,raw_equal_burden_increase,ratio_compensation_credit_krw,equal_compensation_credit_krw,ratio_noshow_charge_krw,equal_noshow_charge_krw,ratio_expected_share_krw,equal_expected_share_krw,ratio_expected_burden_change,equal_expected_burden_change,current_nonpayer");
            groups.println("case_id,group_id,size,remaining,expected_bill,actual_bill,conservation_errors,cancelled_all_absent,ratio_credit_total_krw,ratio_charge_total_krw,equal_credit_total_krw,equal_charge_total_krw,liable_noshow");
            days.println("case_id,requests,matched,expired,unpaid,groups,verification_passes,verification_fails,verification_unavailable,rounds,budget_warning_rounds,engine_ms,max_round_ms,offline_route_calls,conservation_errors");
            for(var c:ExperimentRunner.readCases(input))run(c,cfg.get(c.id),matrix,errors,riders,groups,days);
        }
    }
    static void selfTest() {
        // Exercise the real secondary verifier with a permissive primary candidate: boundary settings must propagate.
        var c=new ExperimentRunner.Case();c.id="test";c.hub=new GeoPoint(37.874,127.205);c.factor=1;c.speed=24;
        c.a=0;c.b=1050;c.c=0;c.tick=1;c.min=1;
        var a=new ExperimentRunner.Input(1,1,new GeoPoint(37.88,127.21),5,600,10000,0,1200);
        var b=new ExperimentRunner.Input(2,2,new GeoPoint(37.89,127.22),6,600,10000,0,1200);
        c.requests.add(a);c.requests.add(b);
        var cfg=new Config(20,.05,1,false,Map.of(1L,new Flags(false,true,false,0),2L,new Flags(false,true,false,0)));
        var s=settings(20,.05);var order=List.of(request(c,a,cfg,s),request(c,b,cfg,s));
        var candidate=new Candidate(order,18,BASE.plusSeconds(1500),List.of(600,1560));
        var m=new ExperimentRunner.Model(c,Map.of(new ExperimentRunner.Pair(1,2),new RoadLeg(13,16*60)));
        // total 18,900 / 20,000 = 5.5% saving, second rider delay 16 min.
        var permissive=new RouteGroupVerifier(m,new HashMap<>(),BASE,null,Map.of(1L,10000L,2L,10000L),Map.of(),BASE,s);
        if(permissive.verify(candidate)!=Verdict.PASS)throw new AssertionError("20min/5% rejected");
        var strictDelay=new RouteGroupVerifier(m,new HashMap<>(),BASE,null,Map.of(1L,10000L,2L,10000L),Map.of(),BASE,settings(15,.05));
        if(strictDelay.verify(candidate)!=Verdict.FAIL)throw new AssertionError("15min accepted 16min");
        var strictSaving=new RouteGroupVerifier(m,new HashMap<>(),BASE,null,Map.of(1L,10000L,2L,10000L),Map.of(),BASE,settings(20,.10));
        if(strictSaving.verify(candidate)!=Verdict.FAIL)throw new AssertionError("10% accepted 5.5%");
        c.b=1000;
        var exactBoundary=new RouteGroupVerifier(m,new HashMap<>(),BASE,null,Map.of(1L,10000L,2L,10000L),Map.of(),BASE,settings(20,.10));
        if(exactBoundary.verify(candidate)!=Verdict.PASS)throw new AssertionError("exact 10% rejected");
        var shares=SettlementCalculator.shares(10001,List.of(2L,3L),1);conserve(shares,10001);
        var original=SettlementCalculator.shares(9000,List.of(1L,1L,1L),2);
        var after=SettlementCalculator.shares(9000,List.of(1L,1L),1);conserve(after,9000);
        if(original.getFirst()!=3000 || after.getFirst()!=4500)throw new AssertionError("fixed-bill noshow burden");
        if(!SettlementCalculator.shares(9000,List.of(1L),0).equals(List.of(9000L)))throw new AssertionError("single survivor");
        if(settings(15,.10).leadMinutes()!=25 || settings(15,.10).reservationSlotMinutes()!=5
                ||settings(15,.10).slotMinutes()!=1)throw new AssertionError("production timeline changed");
        if(PaymentService.prepayAmount(10001)!=11502)throw new AssertionError("production prepay");
        if(groupSeed(1,List.of(1L,64L))==groupSeed(1,List.of(2L,33L)))throw new AssertionError("shared error stream collision");
        SimulationCompensation.selfTest();
        System.out.println("C7 production verifier, settlement and prepayment checks passed");
    }
}
