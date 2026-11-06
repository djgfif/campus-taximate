package com.campustaximate.server.route;

import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.RoadLeg;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 카카오모빌리티 다중 경유지 길찾기. 실제 응답은 1일차 호출로 확인해야 한다. */
public class KakaoRouteClient implements RouteClient {

    private static final int RADIUS_METERS = 10_000;

    private final RestClient client;

    public KakaoRouteClient(RestClient.Builder builder, String baseUrl, String restKey) {
        this.client = builder.baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + restKey)
                .build();
    }

    /** 혼자 경로는 일반 길찾기, 누적 경로는 다중 경유지를 쓴다. 두 API의 쿼터가 다르다. */
    @Override
    public Route route(GeoPoint origin, List<GeoPoint> stops) {
        GeoPoint destination = stops.get(stops.size() - 1);
        try {
            if (stops.size() == 1) {
                return parse(client.get()
                        .uri(uri -> uri.path("/v1/directions")
                                .queryParam("origin", origin.lon() + "," + origin.lat())
                                .queryParam("destination", destination.lon() + "," + destination.lat())
                                .queryParam("priority", "RECOMMEND")
                                .build())
                        .retrieve()
                        .body(JsonNode.class));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("origin", point(origin));
            body.put("destination", point(destination));
            List<Map<String, Double>> waypoints = new ArrayList<>();
            stops.subList(0, stops.size() - 1).forEach(p -> waypoints.add(point(p)));
            body.put("waypoints", waypoints);
            body.put("priority", "RECOMMEND");
            return parse(post("/v1/waypoints/directions", body));
        } catch (RestClientException e) {
            throw new RouteUnavailableException("kakao call failed", e);
        }
    }

    @Override
    public Map<String, RoadLeg> legs(GeoPoint origin, Map<String, GeoPoint> destinations) {
        List<Map<String, Object>> targets = new ArrayList<>();
        destinations.forEach((key, p) -> targets.add(Map.of("key", key, "x", p.lon(), "y", p.lat())));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("origin", point(origin));
        body.put("destinations", targets);
        body.put("radius", RADIUS_METERS);
        body.put("priority", "TIME");
        try {
            return parseLegs(post("/v1/destinations/directions", body));
        } catch (RestClientException e) {
            throw new RouteUnavailableException("kakao call failed", e);
        }
    }

    private JsonNode post(String path, Map<String, Object> body) {
        return client.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    static Map<String, RoadLeg> parseLegs(JsonNode response) {
        Map<String, RoadLeg> legs = new LinkedHashMap<>();
        if (response == null) {
            return legs;
        }
        for (JsonNode route : response.path("routes")) {
            if (route.path("result_code").asInt(-1) == 0) {
                JsonNode summary = route.path("summary");
                legs.put(route.path("key").asText(),
                        new RoadLeg(summary.path("distance").asDouble() / 1000.0, summary.path("duration").asInt()));
            }
        }
        return legs;
    }

    @Override
    public boolean simulated() {
        return false;
    }

    static Route parse(JsonNode response) {
        JsonNode route = response == null ? null : response.path("routes").path(0);
        if (route == null || route.isMissingNode() || route.path("result_code").asInt(-1) != 0) {
            throw new RouteUnavailableException("kakao route not found", null);
        }
        JsonNode summary = route.path("summary");
        JsonNode taxi = summary.path("fare").path("taxi");
        if (!taxi.isNumber()) {
            throw new RouteUnavailableException("kakao taxi fare missing", null);
        }
        return new Route(summary.path("distance").asDouble() / 1000.0,
                summary.path("duration").asInt(), taxi.asLong() + summary.path("fare").path("toll").asLong());
    }

    private static Map<String, Double> point(GeoPoint p) {
        return Map.of("x", p.lon(), "y", p.lat());
    }
}
