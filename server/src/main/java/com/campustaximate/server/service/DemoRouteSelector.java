package com.campustaximate.server.service;
import com.campustaximate.matching.*;
import com.campustaximate.server.common.*;
import com.campustaximate.server.route.RouteClient;
import java.time.Instant;import java.util.*;import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Component;

/** 직선 점뿐 아니라 등록 샘플·도로 형상·같은 하차지까지 시험한 뒤 샘플을 저장한다. */
@Component @RequiredArgsConstructor
public class DemoRouteSelector {
    private final RouteClient routes;private final MatchingSettings settings;private final AppClock clock;
    public boolean realRoutes(){return !routes.simulated();}
    public List<GeoPoint> select(MatchRequest mine,long soloFare,GeoPoint origin,List<Long> memberIds){
        return select(mine,soloFare,origin,memberIds,false);
    }
    public List<GeoPoint> select(MatchRequest mine,long soloFare,GeoPoint origin,List<Long> memberIds,boolean passenger){
        Instant slot=mine.instant()?AppClock.nextMinuteAfter(clock.now()):AppClock.slotOf(clock.now());
        var plan=TripPlan.of(mine.direction(),settings);var initial=plan.schedule(List.of(mine),List.of(mine.soloSeconds()),slot);
        if(!initial.ok())throw ApiException.conflict("DEMO_ROUTE_NOT_FOUND","요청 시간에 맞는 시연 경로를 찾지 못했어요.");
        Instant at=initial.departure();List<GeoPoint> choices=new ArrayList<>();
        for(double ratio:new double[]{0.45,0.72,0.3})choices.add(new GeoPoint(mine.anchor().lat()+(mine.own().lat()-mine.anchor().lat())*ratio,mine.anchor().lon()+(mine.own().lon()-mine.anchor().lon())*ratio));
        DataSeeder.SAMPLE_DROPOFFS.forEach(p->choices.add(new GeoPoint(p.lat(),p.lon())));
        try{var road=routes.routeAt(mine.anchor(),List.of(mine.own()),at).coordinates();for(double ratio:new double[]{0.45,0.72})if(!road.isEmpty())choices.add(road.get(Math.min(road.size()-1,(int)(road.size()*ratio))));}
        catch(RouteClient.RouteUnavailableException ignored){}
        choices.add(mine.own());
        List<GeoPoint> selected=new ArrayList<>();List<MatchRequest> group=new ArrayList<>();group.add(mine);
        Map<Long,Long> fares=new HashMap<>();fares.put(mine.id(),soloFare);Map<Long,GeoPoint> origins=new HashMap<>();origins.put(mine.id(),origin);
        for(int index=0;index<memberIds.size();index++){
            boolean found=false;
            List<GeoPoint> points=new ArrayList<>();
            if(passenger && index==0) {
                DataSeeder.SAMPLE_DROPOFFS.forEach(p->points.add(new GeoPoint(p.lat(),p.lon())));
                for(double ratio:new double[]{1.12,1.25,1.5}) points.add(new GeoPoint(mine.anchor().lat()+(mine.own().lat()-mine.anchor().lat())*ratio,mine.anchor().lon()+(mine.own().lon()-mine.anchor().lon())*ratio));
                points.removeIf(p->mine.anchor().distanceKm(p)<=mine.anchor().distanceKm(mine.own())+0.05);
            } else points.addAll(choices);
            for(GeoPoint point:points.stream().distinct().toList())try{
                var solo=routes.routeAt(mine.anchor(),List.of(point),at);long id=Long.MAX_VALUE-index;
                MatchRequest sample=new MatchRequest(id,memberIds.get(index),mine.direction(),mine.anchorId(),mine.anchor(),mine.gender(),
                    mine.windowStart(),mine.windowEnd(),mine.arriveBy(),mine.arrival()?mine.arriveBy().minusSeconds(solo.seconds()+(settings.leadMinutes()+35)*60L):mine.deadlineSlot(),
                    point,solo.km(),solo.seconds(),Set.of(),0,solo.km(),solo.seconds(),clock.now(),mine.instant(),mine.earliestArrival());
                List<MatchRequest> attempt=new ArrayList<>(group);attempt.add(sample);
                var candidate=new EstimateCalculator(settings).evaluate(attempt,(from,to)->{
                    if(from.own().isSameStop(to.own()))return Optional.of(new RoadLeg(0,0));
                    var leg=routes.routeAt(from.own(),List.of(to.own()),at);return Optional.of(new RoadLeg(leg.km(),leg.seconds()));
                },slot);
                if(candidate.isEmpty())continue;
                long expectedPayer=passenger?memberIds.getFirst():mine.memberId();
                if(candidate.get().order().getLast().memberId()!=expectedPayer)continue;
                fares.put(id,solo.fare());origins.put(id,mine.anchor());
                var verifier=new RouteGroupVerifier(routes,new HashMap<>(),clock.now(),null,fares,origins,slot,settings);
                if(verifier.check(candidate.get()).verdict()!=Verdict.PASS)continue;
                group.add(sample);selected.add(point);found=true;break;
            }catch(RouteClient.RouteUnavailableException ignored){}
            if(!found)throw ApiException.conflict("DEMO_ROUTE_NOT_FOUND","지연·절약 조건을 통과하는 시연 경로를 찾지 못했어요. 탈락 로그를 확인해 주세요.");
        }
        return List.copyOf(selected);
    }
}
