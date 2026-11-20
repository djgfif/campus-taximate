package com.campustaximate.server.service;
import com.campustaximate.matching.*;
import com.campustaximate.server.route.RouteClient;
import com.campustaximate.settlement.SettlementCalculator;
import java.io.*;import java.nio.file.*;import java.time.*;import java.util.*;
/** C9 experiment only: unchanged production selector, verifier, route adapter tariff and settlement. */
public final class DropoffSimulation {
 static final Instant BASE=DetailedSimulation.BASE;
 static final MatchingSettings S=DetailedSimulation.settings(15,.10);
 static final TripPlan PLAN=TripPlan.of(Direction.FROM_HUB,S);
 record Hub(int id,GeoPoint own,double km,int seconds,double walk,double lastKm,int lastSeconds){}
 static final Map<Integer,Map<Integer,Hub>> assignments=new HashMap<>();
 static long fare(ExperimentRunner.Case c,double km,int seconds){return Math.max(1,(long)(Math.ceil(Math.max(c.min,c.a+c.b*km+c.c*seconds/60.)/c.tick)*c.tick));}
 record Last(long cost,double seconds,double walking){}
 static Last last(Hub h,ExperimentRunner.Case c){return h.walk()<=1.2?new Last(0,h.walk()/4.5*3600,h.walk()/4.5*60):new Last(fare(c,h.lastKm(),h.lastSeconds()),h.lastSeconds(),0);}
 static boolean accepts(long share,long last,long solo,double delay,double walking,boolean hybrid,boolean taxi){
  return share+last<solo && (!hybrid || (share+last<=solo*(1-S.minSavingRate())+1e-9 && delay<=S.maxDelayMinutes()+1e-9 && (taxi||last==0)));
 }
 static MatchRequest change(MatchRequest r,Hub h){return new MatchRequest(r.id(),r.memberId(),r.direction(),r.anchorId(),r.anchor(),r.gender(),r.windowStart(),r.windowEnd(),r.arriveBy(),r.deadlineSlot(),h.own(),h.km(),h.seconds(),r.avoidMemberIds(),0,h.km(),h.seconds(),r.requestedAt(),r.instant(),r.earliestArrival());}
 static MatchRequest advance(MatchRequest r,int minutes){var w=OptimalityExport.window(r);return new MatchRequest(r.id(),r.id(),r.direction(),r.anchorId(),r.anchor(),r.gender(),BASE.plusSeconds(w[0]),BASE.plusSeconds(w[1]),null,null,r.own(),r.soloKm(),r.soloSeconds()).requested(r.requestedAt().minusSeconds(minutes*60L),false);}
 static final class Day {
  final ExperimentRunner.Case c;final DetailedSimulation.Config cfg;final ExperimentRunner.Model model;final Map<Long,ExperimentRunner.Input> inputs=new HashMap<>();final Map<Long,Hub> hubs=new HashMap<>();final Map<Long,Long> fares=new HashMap<>();final List<MatchRequest> all;
  final double[] errors;final PrintWriter out;final String policy;final boolean hybrid,taxi;int suggested=0,checks=0;
  Day(ExperimentRunner.Case c,DetailedSimulation.Config cfg,Map<ExperimentRunner.Pair,RoadLeg> matrix,double[] errors,PrintWriter out,String policy,int k){
   this.c=c;this.cfg=cfg;this.errors=errors;this.out=out;this.policy=policy;hybrid=policy.startsWith("C-");taxi=policy.endsWith("taxi");
   var augmented=new ExperimentRunner.Case();augmented.id=c.id;augmented.hub=c.hub;augmented.a=c.a;augmented.b=c.b;augmented.c=c.c;augmented.tick=c.tick;augmented.min=c.min;augmented.factor=c.factor;augmented.speed=c.speed;augmented.requests.addAll(c.requests);
   Set<Integer> added=new HashSet<>();
   for(var r:c.requests){inputs.put(r.id(),r);var h=assignments.get(k).get(r.point());hubs.put(r.id(),h);if(added.add(h.id()))augmented.requests.add(new ExperimentRunner.Input(-h.id(),h.id(),h.own(),h.km(),h.seconds(),fare(c,h.km(),h.seconds()),0,0));}
   model=new ExperimentRunner.Model(augmented,matrix);
   all=c.requests.stream().map(r->{var m=DetailedSimulation.request(c,r,cfg,S);if(policy.startsWith("B-"))m=change(m,hubs.get(r.id()));if(policy.startsWith("D"))m=advance(m,Integer.parseInt(policy.substring(1)));return m;}).toList();
   for(var r:all)fares.put(r.id(),policy.startsWith("B-")?fare(c,r.soloKm(),r.soloSeconds()):inputs.get(r.id()).fare());
  }
  RouteGroupVerifier verifier(List<MatchRequest> rs,Instant slot){Map<Long,Long> quotes=new HashMap<>();for(var r:rs)quotes.put(r.id(),isHub(r)?fare(c,r.soloKm(),r.soloSeconds()):inputs.get(r.id()).fare());return new RouteGroupVerifier(model,new HashMap<>(),slot,null,quotes,Map.of(),slot,S);}
  boolean isHub(MatchRequest r){return r.own().equals(hubs.get(r.id()).own());}
  boolean accepted(Candidate candidate,RouteGroupVerifier.VerifiedRoutes rt,boolean stricter){
   var units=rt.solo().stream().map(RouteClient.Route::fare).toList();var shares=SettlementCalculator.shares(rt.total(),SettlementCalculator.ratioUnits(units),units.size()-1);DetailedSimulation.conserve(shares,rt.total());
   for(int i=0;i<units.size();i++){var r=candidate.order().get(i);var original=inputs.get(r.id());var lm=isHub(r)?last(hubs.get(r.id()),c):new Last(0,0,0);double delay=Math.max(0,(rt.rideSeconds().get(i)+lm.seconds()-original.seconds())/60.);
    if(!accepts(shares.get(i),lm.cost(),original.fare(),delay,lm.walking(),stricter,taxi))return false;
   }return true;
  }
  void emit(Candidate g,RouteGroupVerifier.VerifiedRoutes rt,int second,boolean suggestion){
   var units=rt.solo().stream().map(RouteClient.Route::fare).toList();var expected=SettlementCalculator.shares(rt.total(),SettlementCalculator.ratioUnits(units),units.size()-1);
   double e=errors[new Random(DetailedSimulation.groupSeed(cfg.seed(),g.sortedIds())).nextInt(errors.length)];long bill=DetailedSimulation.actual(rt.total(),e);var actual=SettlementCalculator.shares(bill,SettlementCalculator.ratioUnits(units),units.size()-1);DetailedSimulation.conserve(expected,rt.total());DetailedSimulation.conserve(actual,bill);
   String group=g.sortedIds().toString().replace(", ",";");
   for(int i=0;i<units.size();i++){var r=g.order().get(i);var in=inputs.get(r.id());var lm=isHub(r)?last(hubs.get(r.id()),c):new Last(0,0,0);long solo=DetailedSimulation.actual(in.fare(),cfg.flags().get(r.id()).soloError());
    // Last-mile taxi uses the same quoted tariff; no independent meter residual is invented.
    long cost=actual.get(i)+lm.cost();double delay=Math.max(0,(rt.rideSeconds().get(i)+lm.seconds()-in.seconds())/60.);if(suggestion&&isHub(r))suggested++;
    out.printf(Locale.ROOT,"%s,%s,%d,%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%.12f,%.12f,%d%n",c.id,policy,r.id(),second,group,g.size(),solo,actual.get(i),lm.cost(),cost,expected.get(i),rt.total(),bill,lm.walking(),delay,suggestion&&isHub(r)?1:0);
   }
  }
  record Proposal(Candidate candidate,RouteGroupVerifier.VerifiedRoutes routes,int last,long waited){}
  void subsets(List<MatchRequest> pool,int start,List<MatchRequest> picked,Instant slot,List<Proposal> proposals){
   for(int i=start;i<pool.size();i++){
    picked.add(pool.get(i));if(picked.size()>=2&&picked.stream().anyMatch(r->PLAN.lastChance(r,slot))&&PLAN.confirmable(picked,slot,S.maxGroupSize()))variants(picked,0,new ArrayList<>(),false,slot,proposals);
    if(picked.size()<S.maxGroupSize())subsets(pool,i+1,picked,slot,proposals);picked.removeLast();
   }
  }
  void variants(List<MatchRequest> picked,int at,List<MatchRequest> changed,boolean any,Instant slot,List<Proposal> proposals){
   if(at==picked.size()){
    if(!any)return;LegProvider legs=(a,b)->Optional.of(model.between(a.own(),b.own()));var candidate=new EstimateCalculator(S).evaluate(changed,legs,slot);if(candidate.isEmpty())return;
    var v=verifier(changed,slot);if(v.verify(candidate.get())!=Verdict.PASS)return;checks++;var rt=v.routesOf(candidate.get().order());if(!accepted(candidate.get(),rt,true))return;
    proposals.add(new Proposal(candidate.get(),rt,(int)changed.stream().filter(r->PLAN.lastChance(r,slot)).count(),changed.stream().mapToLong(r->slot.getEpochSecond()-r.requestedAt().getEpochSecond()).sum()));return;
   }
   var r=picked.get(at);changed.add(r);variants(picked,at+1,changed,any,slot,proposals);changed.removeLast();
   var h=hubs.get(r.id());if(!taxi&&h.walk()>1.2)return;
   changed.add(change(r,h));variants(picked,at+1,changed,true,slot,proposals);changed.removeLast();
  }
  void run(){
   Set<Long> done=new HashSet<>();LegProvider legs=(a,b)->Optional.of(model.between(a.own(),b.own()));
   int start=(int)Math.floor(all.stream().mapToLong(r->r.requestedAt().getEpochSecond()-BASE.getEpochSecond()).min().orElse(0)/60.)*60;
   int end=c.requests.stream().mapToInt(ExperimentRunner.Input::deadline).max().orElse(0)+1800;
   for(int second=start;second<=end&&done.size()<all.size();second+=60){Instant slot=BASE.plusSeconds(second);var waiting=all.stream().filter(r->!done.contains(r.id())&&!r.requestedAt().isAfter(slot)).toList();if(waiting.isEmpty())continue;
    var v=verifier(waiting,slot);
    GroupVerifier guarded=new GroupVerifier(){public Verdict verify(Candidate g){return check(g).verdict();}public Verification check(Candidate g){var vr=v.check(g);if(vr.verdict()!=Verdict.PASS)return vr;checks++;if(policy.startsWith("B-")&&!accepted(g,v.routesOf(g.order()),false))return new Verification(Verdict.FAIL,null);return vr;}public boolean recentlyRejected(Candidate g){return v.recentlyRejected(g);}};
    var result=new MatchingRound(S).run(slot,waiting,legs,guarded);
    for(var g:result.confirmed()){var cand=new EstimateCalculator(S).evaluate(g.order(),legs,slot).orElseThrow();emit(cand,v.routesOf(g.order()),second,false);for(var r:g.order())if(!done.add(r.id()))throw new AssertionError("duplicate rider");}
    if(hybrid){var pool=waiting.stream().filter(r->!done.contains(r.id())&&PLAN.eligible(r,slot)).toList();if(pool.stream().anyMatch(r->PLAN.lastChance(r,slot))){List<Proposal> proposals=new ArrayList<>();subsets(pool,0,new ArrayList<>(),slot,proposals);
      proposals.sort(Comparator.comparingInt(Proposal::last).reversed().thenComparing(Comparator.comparingInt((Proposal p)->p.candidate().size()).reversed()).thenComparing(Comparator.comparingLong(Proposal::waited).reversed()).thenComparing(Comparator.comparingDouble((Proposal p)->p.candidate().savedKm()).reversed()).thenComparing(p->p.candidate().sortedIds().toString()));
      for(var p:proposals)if(p.candidate().order().stream().noneMatch(r->done.contains(r.id()))){emit(p.candidate(),p.routes(),second,true);p.candidate().order().forEach(r->done.add(r.id()));}
    }}
    result.expired().forEach(r->done.add(r.id()));
   }
   if(done.size()!=all.size())throw new AssertionError("request accounting "+c.id+" "+policy+" "+done.size());out.flush();System.err.println(c.id+" "+policy+" suggestions="+suggested+" checks="+checks);
  }
 }
 static void selfTest(){
  var c=new ExperimentRunner.Case();c.a=5000;c.b=1000;c.c=0;c.tick=100;c.min=4800;
  if(last(new Hub(1,new GeoPoint(37,127),1,100,1.2,2,300),c).cost()!=0)throw new AssertionError("walk boundary");
  if(last(new Hub(1,new GeoPoint(37,127),1,100,1.201,2,300),c).cost()!=7000)throw new AssertionError("taxi tariff");
  if(accepts(9000,1000,10000,0,0,false,true)||!accepts(8999,1000,10000,100,0,false,true))throw new AssertionError("IR");
  if(!accepts(8000,1000,10000,15,0,true,true)||accepts(8001,1000,10000,15,0,true,true)||accepts(8000,1000,10000,15.01,0,true,true)||accepts(8000,1000,10000,15,0,true,false))throw new AssertionError("hybrid rules");
  var shares=SettlementCalculator.shares(12347,SettlementCalculator.ratioUnits(List.of(9000L,12000L,14000L)),2);DetailedSimulation.conserve(shares,12347);System.out.println("C9 self-test PASS");
 }
 public static void main(String[] args)throws Exception{
  if(args[0].equals("--self-test")){selfTest();return;}
  if(args[0].equals("--accept")){System.out.println(accepts(Long.parseLong(args[1]),Long.parseLong(args[2]),Long.parseLong(args[3]),Double.parseDouble(args[4]),0,Boolean.parseBoolean(args[5]),Boolean.parseBoolean(args[6])));return;}
  if(args[0].equals("--last")){var c=new ExperimentRunner.Case();c.a=5000;c.b=1000;c.c=0;c.tick=100;c.min=4800;var lm=last(new Hub(1,new GeoPoint(37,127),1,100,Double.parseDouble(args[1]),2,300),c);System.out.println(lm.cost()+","+lm.seconds()+","+lm.walking());return;}
  var matrix=C7RoadMatrix.read(Path.of(args[1]));var extra=ExperimentRunner.readMatrix(Path.of(args[2]));Map<ExperimentRunner.Pair,RoadLeg> combined=new AbstractMap<>(){public RoadLeg get(Object key){var value=extra.get(key);return value!=null?value:matrix.get(key);}public boolean isEmpty(){return false;}public Set<Entry<ExperimentRunner.Pair,RoadLeg>> entrySet(){throw new UnsupportedOperationException();}};
  for(var line:Files.readAllLines(Path.of(args[3]))){if(line.startsWith("k\t"))continue;var p=line.split("\t");assignments.computeIfAbsent(Integer.parseInt(p[0]),x->new HashMap<>()).put(Integer.parseInt(p[1]),new Hub(Integer.parseInt(p[2]),new GeoPoint(Double.parseDouble(p[3]),Double.parseDouble(p[4])),Double.parseDouble(p[5]),Integer.parseInt(p[6]),Double.parseDouble(p[7]),Double.parseDouble(p[8]),Integer.parseInt(p[9])));}
  var configs=DetailedSimulation.configs(Path.of(args[0]));double[] errors;try(var lines=Files.lines(Path.of(args[4]))){errors=lines.mapToDouble(Double::parseDouble).toArray();}
  int k=Integer.parseInt(args[6]);try(var out=new PrintWriter(Files.newBufferedWriter(Path.of(args[5])))){out.println("case_id,policy,id,second,group,size,solo,shared_share,last_cost,total_cost,expected_share,expected_bill,actual_bill,walking,delay,suggestion");
   for(var c:ExperimentRunner.readCases(Path.of(args[0]))){for(String policy:List.of("A","B-5","B-10","B-20","C-walk","C-taxi","D0","D15","D30"))new Day(c,configs.get(c.id),combined,errors,out,policy,policy.startsWith("B-")?Integer.parseInt(policy.substring(2)):k).run();}
  }
 }
}
