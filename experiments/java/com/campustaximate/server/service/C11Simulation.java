package com.campustaximate.server.service;

import com.campustaximate.matching.*;
import com.campustaximate.server.route.RouteClient;
import com.campustaximate.server.route.RouteClient.Route;
import com.campustaximate.settlement.SettlementCalculator;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Experiment-only timing gates and marked shared quotes. All selection/feasibility remains production. */
public final class C11Simulation {
    static final Instant BASE=DetailedSimulation.BASE;
    static final double[] MARGINS={0,.005,.01,.02,.03,.05};
    static final Map<String,Double> rates=new HashMap<>(),gains=new HashMap<>();
    static double bias;
    /** C12: 지금 서비스 그대로(마감 확정 = 운영 기본 earlyConfirmMinutes, 확정 분담금 여유분). */
    static double currentMargin=.01;
    static MatchingSettings settings(int early) {
        var s=MatchingSettings.everyMinute();return new MatchingSettings(s.maxDelayMinutes(),s.minSavingRate(),s.maxCandidates(),
            s.maxGroupSize(),s.maxVerifications(),s.leadMinutes(),s.slotMinutes(),s.reservationSlotMinutes(),early,s.searchMillis(),
            s.instantMatchMinutes(),s.instantPrepMinutes(),s.instantDispatchMinutes());
    }
    static int sector(GeoPoint hub,GeoPoint own) {
        double a=hub.bearingTo(own);return Math.floorMod((int)Math.floor(a/(Math.PI/6)),12);
    }
    static int band(double km){return km<10?0:km<25?1:2;}
    static String rateKey(int n,int hour,int sector,int band){return n+":"+hour+":"+sector+":"+band;}
    static double lambda(List<MatchRequest> group,Instant slot,MatchingSettings s,int n) {
        var plan=TripPlan.of(Direction.FROM_HUB,s);var payer=plan.payer(plan.order(group));
        Instant deadline=group.stream().map(plan::urgency).min(Instant::compareTo).orElseThrow();
        long from=slot.getEpochSecond()-BASE.getEpochSecond(),to=deadline.getEpochSecond()-BASE.getEpochSecond();double expected=0;
        while(from<to){int hour=12+(int)(from/3600);long end=Math.min(to,(from/3600+1)*3600);
            expected+=rates.getOrDefault(rateKey(n,hour,sector(payer.anchor(),payer.own()),band(payer.soloKm())),0.)*(end-from)/60.;from=end;}
        return expected;
    }
    static boolean allowed(String policy,List<MatchRequest> group,Instant slot,MatchingSettings s,int n) {
        var plan=TripPlan.of(Direction.FROM_HUB,s);
        if(plan.isDeadline(group,slot))return true;
        if(policy.equals("WAIT-TO-DEADLINE"))return false;
        if(!policy.equals("FORECAST-AWARE"))return true;
        double extra=gains.getOrDefault(n+":"+group.size(),0.);
        return -Math.expm1(-lambda(group,slot,s,n))*extra<=.01;
    }
    static long marked(long quote,double margin){return Math.max(1,Math.round(quote*(1+bias+margin)));}
    /** The rider's alternative is a solo taxi with the same expected meter bias, so solo quotes get (1+bias) but no margin. */
    static long markedSolo(long quote){return Math.max(1,Math.round(quote*(1+bias)));}
    static boolean minSaving(List<Long> fixed,List<Long> solo) {
        for(int i=0;i<fixed.size();i++)if(Math.multiplyExact(fixed.get(i),10)>Math.multiplyExact(solo.get(i),9))return false;
        return true;
    }
    /** First route call in each FROM_HUB verification is the shared route, even with duplicate endpoints. */
    static final class MarkedRoute implements RouteClient {
        final ExperimentRunner.Model model; final double margin; final boolean mark;boolean first;long raw;
        MarkedRoute(ExperimentRunner.Model model,double margin,boolean mark){this.model=model;this.margin=margin;this.mark=mark;}
        void begin(){first=true;}
        public Route route(GeoPoint origin,List<GeoPoint> stops){var r=model.route(origin,stops);if(first){first=false;raw=r.fare();return mark?r.withFare(marked(raw,margin)):r;}return r;}
        public Map<String,RoadLeg> legs(GeoPoint o,Map<String,GeoPoint> d){return model.legs(o,d);}
        public boolean simulated(){return true;}
    }
    static String join(Collection<?> x){return String.join(";",x.stream().map(Object::toString).toList());}
    static void day(ExperimentRunner.Case c,DetailedSimulation.Config cfg,Map<ExperimentRunner.Pair,RoadLeg> matrix,
                    double[] errors,String policy,PrintWriter out,PrintWriter days) {
        boolean current=policy.equals("CURRENT");
        boolean markup=policy.startsWith("MARKUP")||current;double margin=current?currentMargin:markup?Double.parseDouble(policy.substring(6)):0;
        int early=current?MatchingSettings.everyMinute().earlyConfirmMinutes():policy.startsWith("EARLY")?Integer.parseInt(policy.substring(5)):
            policy.equals("WAIT-TO-DEADLINE")||policy.equals("FORECAST-AWARE")?0:10;
        var s=settings(early);var plan=TripPlan.of(Direction.FROM_HUB,s);
        var all=c.requests.stream().map(r->DetailedSimulation.request(c,r,cfg,s)).toList();
        var model=new ExperimentRunner.Model(c,matrix);var routes=new MarkedRoute(model,margin,markup);
        Map<Long,Long> fares=new HashMap<>();c.requests.forEach(r->fares.put(r.id(),r.fare()));
        Map<Long,Long> shown=new HashMap<>();fares.forEach((id,f)->shown.put(id,markup?markedSolo(f):f));
        Set<Long> finished=new HashSet<>();Map<RouteGroupVerifier.RejectionKey,Instant> rejected=new HashMap<>();
        LegProvider legs=(a,b)->Optional.of(model.between(a.own(),b.own()));int groups=0,matched=0,expired=0,rounds=0,warnings=0,parity=0;
        int end=c.requests.stream().mapToInt(ExperimentRunner.Input::deadline).max().orElse(0)+1200;
        for(int second=0;second<=end && finished.size()<all.size();second+=60){
            Instant slot=BASE.plusSeconds(second);var waiting=all.stream().filter(r->!finished.contains(r.id())&&!r.requestedAt().isAfter(slot)).toList();
            if(waiting.isEmpty())continue;
            var verifier=new RouteGroupVerifier(routes,rejected,slot,null,shown,Map.of(),slot,s);
            Map<List<MatchRequest>,Long> rawQuotes=new HashMap<>();
            GroupVerifier gate=new GroupVerifier(){
                public Verdict verify(Candidate g){return check(g).verdict();}
                public boolean recentlyRejected(Candidate g){return !allowed(policy,g.order(),slot,s,all.size())||verifier.recentlyRejected(g);}
                public Verification check(Candidate g){
                    if(!allowed(policy,g.order(),slot,s,all.size()))throw new AssertionError("gate bypass");
                    routes.begin();var vr=verifier.check(g);if(vr.verdict()!=Verdict.PASS)return vr;
                    var rt=verifier.routesOf(g.order());rawQuotes.put(g.order(),routes.raw);
                    if(markup){var units=rt.solo().stream().map(Route::fare).toList();var fixed=SettlementCalculator.shares(rt.total(),SettlementCalculator.ratioUnits(units),units.size()-1);
                        if(!minSaving(fixed,units))return new Verification(Verdict.FAIL,null);}
                    return vr;
                }
            };
            long start=System.nanoTime();var result=new MatchingRound(s).run(slot,waiting,legs,gate);double ms=(System.nanoTime()-start)/1e6;
            rounds++;if(ms>=s.searchMillis())warnings++;
            for(var g:result.confirmed()){
                var rt=verifier.routesOf(g.order());long raw=rawQuotes.get(g.order());
                var candidate=new EstimateCalculator(s).evaluate(g.order(),legs,slot).orElseThrow();
                var independent=new RouteGroupVerifier(model,new HashMap<>(),slot,null,fares,Map.of(),slot,s);
                if(independent.verify(candidate)!=Verdict.PASS)throw new AssertionError("production feasibility parity");parity++;
                if(policy.equals("WAIT-TO-DEADLINE")&&!plan.isDeadline(g.order(),slot))throw new AssertionError("early deadline confirm");
                // Record raw solo quotes; the marked solo quotes were only the saving-check baseline.
                var units=g.order().stream().map(r->fares.get(r.id())).toList();
                long errorSeed=DetailedSimulation.groupSeed(cfg.seed(),g.order().stream().map(MatchRequest::id).sorted().toList());
                int ei=new Random(errorSeed).nextInt(errors.length);long bill=DetailedSimulation.actual(raw,errors[ei]);
                var actual=SettlementCalculator.shares(bill,SettlementCalculator.ratioUnits(units),units.size()-1);
                var expected=SettlementCalculator.shares(raw,SettlementCalculator.ratioUnits(units),units.size()-1);
                var soloErrors=g.order().stream().map(r->cfg.flags().get(r.id()).soloError()).toList();
                List<Long> solos=new ArrayList<>();List<Double> delays=new ArrayList<>();List<Double> waits=new ArrayList<>();
                for(int i=0;i<units.size();i++){var r=g.order().get(i);solos.add(DetailedSimulation.actual(units.get(i),soloErrors.get(i)));
                    delays.add(Math.max(0,(rt.rideSeconds().get(i)-rt.solo().get(i).seconds())/60.));
                    waits.add((slot.getEpochSecond()-r.requestedAt().getEpochSecond())/60.);
                    if(!finished.add(r.id()))throw new AssertionError("duplicate matched");}
                long departure=rt.departure().getEpochSecond()-BASE.getEpochSecond();
                out.println(String.join("	",c.id,policy,""+(++groups),join(g.order().stream().map(MatchRequest::id).toList()),""+second,
                    ""+departure,""+(departure+rt.rideSeconds().getLast()),""+raw,""+rt.total(),""+bill,""+ei,join(units),join(soloErrors),
                    join(actual),join(expected),join(solos),join(delays),join(waits)));
                matched+=g.order().size();
            }
            for(var r:result.expired()){if(!finished.add(r.id()))throw new AssertionError("duplicate expired");expired++;}
        }
        if(matched+expired!=all.size()||finished.size()!=all.size())throw new AssertionError("request conservation");
        days.println(String.join(",",c.id,policy,""+all.size(),""+matched,""+expired,""+groups,""+rounds,""+warnings,""+parity));
        out.flush();days.flush();System.err.println("C11 "+c.id+" "+policy+" matched="+matched);
    }
    static void forecast(Path path)throws IOException {
        for(String line:Files.readAllLines(path)){var p=line.split("	");if(p[0].equals("R"))rates.put(p[1],Double.parseDouble(p[2]));
            if(p[0].equals("G"))gains.put(p[1],Double.parseDouble(p[2]));}
    }
    static void selfTest(){
        bias=.06;
        var units=List.of(10000L,10000L);
        var base=SettlementCalculator.shares(18000,units,1);if(!minSaving(base,units))throw new AssertionError("base saving boundary");
        var fixed=SettlementCalculator.shares(marked(18000,.02),units,1);if(minSaving(fixed,units))throw new AssertionError("markup ignored");
        if(!minSaving(SettlementCalculator.shares(marked(16000,.02),units,1),units))throw new AssertionError("valid markup rejected");
        var c=new ExperimentRunner.Case();c.id="test";c.hub=new GeoPoint(37.874,127.205);c.factor=1;c.speed=24;c.a=0;c.b=1000;c.c=0;c.tick=1;c.min=1;
        var a=new ExperimentRunner.Input(1,1,new GeoPoint(37.88,127.21),5,600,10000,0,1200);
        var d=new ExperimentRunner.Input(2,2,new GeoPoint(37.89,127.22),6,600,10000,0,1200);c.requests.addAll(List.of(a,d));
        var cfg=new DetailedSimulation.Config(15,.1,1,false,1,Map.of(1L,new DetailedSimulation.Flags(false,true,false,0),2L,new DetailedSimulation.Flags(false,true,false,0)));
        var s=settings(0);var order=List.of(DetailedSimulation.request(c,a,cfg,s),DetailedSimulation.request(c,d,cfg,s));
        var cand=new Candidate(order,18,BASE.plusSeconds(1500),List.of(600,1200));
        var model=new ExperimentRunner.Model(c,Map.of(new ExperimentRunner.Pair(1,2),new RoadLeg(13,600)));
        var fares=Map.of(1L,10000L,2L,10000L);
        if(new RouteGroupVerifier(model,new HashMap<>(),BASE,null,fares,Map.of(),BASE,s).verify(cand)!=Verdict.PASS)throw new AssertionError("raw quote boundary");
        var mr=new MarkedRoute(model,.02,true);mr.begin();
        if(new RouteGroupVerifier(mr,new HashMap<>(),BASE,null,fares,Map.of(),BASE,s).verify(cand)!=Verdict.FAIL)throw new AssertionError("markup must enter production saving check");
        var bo=new MarkedRoute(model,0,true);bo.begin();var shownFares=Map.of(1L,markedSolo(10000),2L,markedSolo(10000));
        if(new RouteGroupVerifier(bo,new HashMap<>(),BASE,null,shownFares,Map.of(),BASE,s).verify(cand)!=Verdict.PASS)throw new AssertionError("bias must apply to solo baseline too");
        if(allowed("WAIT-TO-DEADLINE",order,BASE.plusSeconds(900),s,2)||!allowed("WAIT-TO-DEADLINE",order,BASE.plusSeconds(1200),s,2))throw new AssertionError("deadline gate");
        System.out.println("C11 markup minimum-saving checks passed");
    }
    public static void main(String[] args)throws Exception {
        if(args[0].equals("--self-test")){selfTest();return;}
        bias=Double.parseDouble(args[6]);if(!args[5].equals("-"))forecast(Path.of(args[5]));
        var cases=ExperimentRunner.readCases(Path.of(args[0]));var cfg=DetailedSimulation.configs(Path.of(args[0]));
        var matrix=C7RoadMatrix.read(Path.of(args[1]));double[] errors=Files.readAllLines(Path.of(args[2])).stream().filter(x->!x.isBlank()).mapToDouble(Double::parseDouble).toArray();
        try(var out=new PrintWriter(Files.newBufferedWriter(Path.of(args[3])));var days=new PrintWriter(Files.newBufferedWriter(Path.of(args[4])))){
            out.println("case_id	policy	group_id	ids	confirm	departure	finish	raw_quote	quote	bill	error_index	units	solo_errors	actual	expected	solos	delays	waits");
            days.println("case_id,policy,requests,matched,expired,groups,rounds,budget_warnings,production_parity_groups");
            for(var c:cases){List<String> policies=new ArrayList<>(List.of("PROD"));
                if(args.length>7){policies=new ArrayList<>(List.of(args[7].split(",")));if(args.length>8)currentMargin=Double.parseDouble(args[8]);}
                else if(!args[5].equals("-")){policies.addAll(List.of("WAIT-TO-DEADLINE","EARLY0","EARLY5","EARLY10","EARLY15","EARLY20","FORECAST-AWARE"));
                    for(double margin:MARGINS)policies.add("MARKUP"+margin);}
                for(String policy:policies)day(c,cfg.get(c.id),matrix,errors,policy,out,days);
            }
        }
    }
}
