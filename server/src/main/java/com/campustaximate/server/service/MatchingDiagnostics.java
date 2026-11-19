package com.campustaximate.server.service;
import com.campustaximate.matching.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 엔진을 바꾸지 않고 같은 풀·최대 12명 후보의 1차 탈락을 진단한다. 외부 조회는 하지 않는다. */
@Slf4j @Component @RequiredArgsConstructor
public class MatchingDiagnostics {
    private final MatchingSettings settings;
    public static void reason(Instant slot,List<Long> ids,String code,Object values){log.info("매칭 진단 slot={} requests={} code={} values={}",slot,ids,code,values);}
    public void inspect(Instant slot,List<MatchRequest> input,LegProvider legs){
        Map<MatchRequest.PoolKey,List<MatchRequest>> pools=new LinkedHashMap<>();Set<String> seen=new HashSet<>();
        var byId=input.stream().collect(java.util.stream.Collectors.groupingBy(MatchRequest::id));var ids=byId.keySet().stream().sorted().toList();
        for(int i=0;i<ids.size();i++)for(int j=i+1;j<ids.size();j++){
            var a=byId.get(ids.get(i));var b=byId.get(ids.get(j));
            if(a.stream().noneMatch(x->b.stream().anyMatch(y->x.poolKey().equals(y.poolKey()))))reason(slot,List.of(ids.get(i),ids.get(j)),"POOL_MISMATCH",0);
        }
        for(var r:input) {
            if(!TripPlan.of(r.direction(),settings).eligible(r,slot)){reason(slot,List.of(r.id()),"TIME_ELIGIBILITY",r.requestedAt());continue;}
            pools.computeIfAbsent(r.poolKey(),k->new ArrayList<>()).add(r);
        }
        for(var pool:pools.values())for(var anchor:pool){
            for(var other:pool)if(other.id()!=anchor.id()&&anchor.avoids(other)){
                var blocked=java.util.stream.Stream.of(anchor.id(),other.id()).sorted().toList();
                if(seen.add(blocked+"@"+anchor.anchorId()))reason(slot,blocked,"BLOCKED",2);
            }
            double bearing=anchor.anchor().bearingTo(anchor.own());
            var nearest=pool.stream().filter(r->r.id()!=anchor.id()&&r.memberId()!=anchor.memberId()&&!anchor.avoids(r))
                .sorted(Comparator.<MatchRequest>comparingDouble(r->angle(bearing,anchor.anchor().bearingTo(r.own()))).thenComparingLong(MatchRequest::id))
                .limit(settings.maxCandidates()-1).toList();
            for(int size=2;size<=settings.maxGroupSize();size++)collect(slot,anchor,nearest,0,size-1,new ArrayList<>(),legs,seen);
        }
    }
    private static double angle(double a,double b){double gap=Math.abs(a-b)%(Math.PI*2);return Math.min(gap,Math.PI*2-gap);}
    private void collect(Instant slot,MatchRequest anchor,List<MatchRequest> pool,int start,int need,List<MatchRequest> picked,LegProvider legs,Set<String> seen){
        if(need>0){for(int i=start;i<=pool.size()-need;i++){picked.add(pool.get(i));collect(slot,anchor,pool,i+1,need-1,picked,legs,seen);picked.removeLast();}return;}
        List<MatchRequest> group=new ArrayList<>(picked);group.add(anchor);var ids=group.stream().map(MatchRequest::id).sorted().toList();
        if(!seen.add(ids+"@"+anchor.anchorId()))return;
        for(var a:group)for(var b:group)if(a.id()!=b.id()&&(a.memberId()==b.memberId()||a.avoids(b))){reason(slot,ids,"BLOCKED",group.size());return;}
        TripPlan plan=TripPlan.of(anchor.direction(),settings);
        if(!plan.confirmable(group,slot,settings.maxGroupSize())){reason(slot,ids,"CONFIRMATION_TIME",group.size());return;}
        var order=plan.order(group);List<RoadLeg> roads=new ArrayList<>();
        for(int i=1;i<order.size();i++){var leg=legs.leg(order.get(i-1),order.get(i));if(leg.isEmpty()){reason(slot,ids,"LEG_UNAVAILABLE",i);return;}roads.add(leg.get());}
        var rides=plan.rideSeconds(order,roads);
        for(int i=0;i<order.size();i++){int delay=order.get(i).walkSeconds()+rides.get(i)-order.get(i).soloSeconds();if(delay>settings.maxDelayMinutes()*60){reason(slot,ids,"DELAY",delay);return;}}
        if(!plan.schedule(order,rides,slot).ok())reason(slot,ids,"TIME_WINDOW",rides);
    }
}
