package com.campustaximate.server.route;
import com.campustaximate.matching.GeoPoint;
import com.campustaximate.server.common.AppClock;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 서버 한 대의 보행 경로 캐시·일일 호출량. 응답과 로그에 출발 좌표·키를 남기지 않는다.
 * 조회 실패와 일일 한도 초과는 추정값으로 대신하지 않고 WalkUnavailableException(503)으로 요청을 받지 않는다.
 * 실패는 캐시하지 않아 다음 요청에서 다시 조회한다.
 * 출발지와 승차 지점이 SAME_PLACE_METERS 이내면 TMAP을 부르지 않는다. TMAP은 같은 좌표를
 * "waypoints are too near"(400, code 1007)로 거절하므로, 지점 앞에서 요청하면 항상 503이 되던 문제를 막는다.
 * 이 경우는 장애가 아니라 이미 지점에 있는 것이므로 보수 추정(1분, "추정" 표시)을 쓴다(S44 보완, 2026-11-19).
 */
@Slf4j
public class TmapWalkClient implements WalkEstimator {
    static final double SAME_PLACE_METERS = 10;
    private static final WalkEstimator NEAR = new ConservativeWalkEstimator();
    private record Key(long lat,long lon,GeoPoint point) {}
    private record Cached(Walk walk,Instant until) {}
    private final RestClient client; private final AppClock clock; private final Duration keep;
    private final Map<Key,Cached> cache=new java.util.concurrent.ConcurrentHashMap<>();
    private LocalDate day; private int used; private final int dailyLimit;
    public TmapWalkClient(RestClient client,AppClock clock,Duration keep,int dailyLimit){this.client=client;this.clock=clock;this.keep=keep;this.dailyLimit=dailyLimit;}
    @Override public Walk estimate(GeoPoint origin,GeoPoint point){
        if(origin.distanceKm(point)*1000<=SAME_PLACE_METERS)return NEAR.estimate(origin,point);
        Instant now=clock.now();cache.values().removeIf(c->!now.isBefore(c.until()));
        Key key=new Key(Math.round(origin.lat()*10000),Math.round(origin.lon()*10000),point);
        // 같은 키만 한 번 조회하고, 다른 출발지의 HTTP 응답은 서로 기다리지 않는다.
        return cache.compute(key,(k,saved)->{
            if(saved!=null&&clock.now().isBefore(saved.until()))return saved;
            if(!reserve(clock.now())){log.info("TMAP 보행 일일 한도 초과 code=WALK_UNAVAILABLE");throw new WalkUnavailableException();}
            Walk walk=fetch(origin,point);
            return new Cached(walk,clock.now().plus(keep));
        }).walk();
    }
    private synchronized boolean reserve(Instant now){
        LocalDate today=now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();if(!today.equals(day)){day=today;used=0;}
        if(used>=dailyLimit)return false;
        used++;if(used==Math.max(1,(dailyLimit*8+9)/10))log.warn("TMAP 보행 일일 사용량 80% 도달 day={} count={} limit={}",day,used,dailyLimit);
        return true;
    }
    private Walk fetch(GeoPoint origin,GeoPoint point){
        try{
            JsonNode body=client.post().uri("/tmap/routes/pedestrian?version=1").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("startX",origin.lon(),"startY",origin.lat(),"endX",point.lon(),"endY",point.lat(),
                        "reqCoordType","WGS84GEO","resCoordType","WGS84GEO","startName","출발지","endName","승차지점"))
                .retrieve().body(JsonNode.class);
            JsonNode seconds=body==null?null:body.path("features").path(0).path("properties").path("totalTime");
            if(seconds==null||!seconds.canConvertToInt()||seconds.asInt()<0)throw new IllegalArgumentException("보행 시간 누락");
            return new Walk(seconds.asInt(),false);
        }catch(RuntimeException failure){
            // 좌표·키는 남기지 않고, 원인을 찾을 수 있게 HTTP 상태와 TMAP 오류 코드만 남긴다.
            log.info("TMAP 보행 조회 실패 code=WALK_UNAVAILABLE cause={}",describe(failure));throw new WalkUnavailableException();
        }
    }
    static String describe(RuntimeException failure){
        if(failure instanceof RestClientResponseException http){
            java.util.regex.Matcher code=java.util.regex.Pattern.compile("\"code\"\\s*:\\s*\"(\\w+)\"").matcher(http.getResponseBodyAsString());
            return "http="+http.getStatusCode().value()+(code.find()?" tmap="+code.group(1):"");
        }
        return failure.getClass().getSimpleName();
    }
    public synchronized int dailyUsage(){LocalDate today=clock.now().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();if(!today.equals(day)){day=today;used=0;}return used;}
}
