package com.campustaximate.server.route;

import com.campustaximate.matching.GeoPoint;
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

    private final RestClient client;

    public KakaoRouteClient(RestClient.Builder builder, String baseUrl, String restKey) {
        this.client = builder.baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + restKey)
                .build();
    }

    @Override
    public Route route(GeoPoint origin, List<GeoPoint> stops) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("origin", point(origin));
        body.put("destination", point(stops.get(stops.size() - 1)));
        List<Map<String, Double>> waypoints = new ArrayList<>();
        stops.subList(0, stops.size() - 1).forEach(p -> waypoints.add(point(p)));
        body.put("waypoints", waypoints);
        body.put("priority", "RECOMMEND");
        try {
            JsonNode response = client.post().uri("/v1/waypoints/directions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return parse(response);
        } catch (RestClientException e) {
            throw new RouteUnavailableException("kakao call failed", e);
        }
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
                summary.path("duration").asInt(), taxi.asLong());
    }

    private static Map<String, Double> point(GeoPoint p) {
        return Map.of("x", p.lon(), "y", p.lat());
    }
}
