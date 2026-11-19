package com.campustaximate.server.route;

import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.RoadLeg;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 카카오모빌리티 다중 경유지 길찾기. 실제 응답은 1일차 호출로 확인해야 한다. */
public class KakaoRouteClient implements RouteClient {

    private static final int RADIUS_METERS = 10_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Duration MIN_FUTURE = Duration.ofMinutes(10);
    private static final DateTimeFormatter DEPARTURE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneId.of("Asia/Seoul"));

    private final RestClient client;
    private final int retries;

    public KakaoRouteClient(RestClient.Builder builder, String baseUrl, String restKey) {
        this(builder, baseUrl, restKey, TIMEOUT, 1);
    }

    /** 모의 전송 계층을 주입하는 테스트용 생성자. */
    KakaoRouteClient(RestClient client, int retries) {
        this.client = client;
        this.retries = Math.max(0, retries);
    }

    /** 슬롯 잠금 안에서 호출되므로 응답을 무한정 기다리지 않는다. */
    KakaoRouteClient(RestClient.Builder builder, String baseUrl, String restKey, Duration timeout) {
        this(builder, baseUrl, restKey, timeout, 1);
    }

    KakaoRouteClient(RestClient.Builder builder, String baseUrl, String restKey, Duration timeout, int retries) {
        this.retries = Math.max(0, retries);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        this.client = builder.requestFactory(factory).baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + restKey)
                .build();
    }

    /** 단독 경로는 일반 길찾기, 누적 경로는 다중 경유지를 쓴다. 두 API의 쿼터가 다르다. */
    @Override
    public Route route(GeoPoint origin, List<GeoPoint> requested) {
        return retry(() -> routeOnce(origin, requested));
    }

    private Route routeOnce(GeoPoint origin, List<GeoPoint> requested) {
        List<GeoPoint> stops = mergeStops(requested);
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

    /** 미래 운행 정보는 경유지와 출발 시각을 함께 받는다. 곧 출발이면 지금 기준으로 조회한다. */
    @Override
    public Route routeAt(GeoPoint origin, List<GeoPoint> requested, Instant departure) {
        if (departure == null || departure.isBefore(Instant.now().plus(MIN_FUTURE))) {
            return route(origin, requested);
        }
        return retry(() -> futureOnce(origin, requested, departure));
    }

    private Route futureOnce(GeoPoint origin, List<GeoPoint> requested, Instant departure) {
        List<GeoPoint> stops = mergeStops(requested);
        GeoPoint destination = stops.get(stops.size() - 1);
        String waypoints = stops.subList(0, stops.size() - 1).stream()
                .map(p -> p.lon() + "," + p.lat())
                .collect(java.util.stream.Collectors.joining("|"));
        try {
            return parse(client.get()
                    .uri(uri -> {
                        uri.path("/v1/future/directions")
                                .queryParam("origin", origin.lon() + "," + origin.lat())
                                .queryParam("destination", destination.lon() + "," + destination.lat())
                                .queryParam("departure_time", DEPARTURE_FORMAT.format(departure))
                                .queryParam("priority", "RECOMMEND");
                        if (!waypoints.isEmpty()) {
                            uri.queryParam("waypoints", waypoints);
                        }
                        return uri.build();
                    })
                    .retrieve()
                    .body(JsonNode.class));
        } catch (RestClientException e) {
            throw new RouteUnavailableException("kakao call failed", e);
        }
    }

    /** 이어지는 같은 정차 지점은 한 번만 넣는다. */
    static List<GeoPoint> mergeStops(List<GeoPoint> stops) {
        return GeoPoint.mergeStops(stops);
    }

    @Override
    public Map<String, RoadLeg> legs(GeoPoint origin, Map<String, GeoPoint> destinations) {
        return retry(() -> legsOnce(origin, destinations));
    }

    private Map<String, RoadLeg> legsOnce(GeoPoint origin, Map<String, GeoPoint> destinations) {
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

    /** 통신 실패만 정해진 횟수만큼 다시 조회한다. */
    private <T> T retry(java.util.function.Supplier<T> call) {
        for (int attempt = 0; ; attempt++) {
            try {
                return call.get();
            } catch (RouteUnavailableException e) {
                if (attempt >= retries) throw e;
            }
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
        List<GeoPoint> coordinates = new ArrayList<>();
        List<Integer> legSeconds = new ArrayList<>();
        for (JsonNode section : route.path("sections")) {
            legSeconds.add(section.path("duration").asInt());
            for (JsonNode road : section.path("roads")) {
                JsonNode vertexes = road.path("vertexes");
                for (int i = 0; i + 1 < vertexes.size(); i += 2) {
                    coordinates.add(new GeoPoint(vertexes.get(i + 1).asDouble(), vertexes.get(i).asDouble()));
                }
            }
        }
        return new Route(summary.path("distance").asDouble() / 1000.0,
                summary.path("duration").asInt(), taxi.asLong() + summary.path("fare").path("toll").asLong(), coordinates,
                legSeconds);
    }

    private static Map<String, Double> point(GeoPoint p) {
        return Map.of("x", p.lon(), "y", p.lat());
    }
}
