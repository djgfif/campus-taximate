package com.campustaximate.server.web;

import com.campustaximate.server.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 카카오 로컬 키워드 검색을 서버 REST 키로 대리 호출한다.
 * 브라우저 JS 키가 127.0.0.1 등 미등록 호스트에서 도메인 불일치로 차단되는 경우에도 장소 검색이 동작한다.
 */
@RestController
public class PlaceSearchController {

    private final RestClient client;
    private final boolean enabled;

    public record Place(String id, String name, String address, double lat, double lon) {
    }

    @Autowired
    public PlaceSearchController(
            RestClient.Builder builder,
            @Value("${app.kakao.rest-key:}") String restKey,
            @Value("${app.kakao.local-base-url:https://dapi.kakao.com}") String localBaseUrl,
            @Value("${app.kakao.timeout-ms:3000}") long timeoutMillis) {
        String cleanKey = restKey == null ? "" : restKey.trim();
        this.enabled = !cleanKey.isEmpty();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeout = (int) Math.max(500L, timeoutMillis);
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = builder
                .requestFactory(factory)
                .baseUrl(localBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + cleanKey)
                .build();
    }

    PlaceSearchController(RestClient client, boolean enabled) {
        this.client = client;
        this.enabled = enabled;
    }

    @GetMapping("/api/places/search")
    public List<Place> search(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon,
            HttpSession session) {
        if (!(session.getAttribute(LoginTokenFilter.SESSION_KEY) instanceof Long)) {
            throw ApiException.unauthorized();
        }
        if ((lat == null) != (lon == null)) {
            throw ApiException.badRequest("INVALID_COORDINATE", "기준 좌표를 확인해 주세요.");
        }
        if (lat != null && (!Double.isFinite(lat) || !Double.isFinite(lon)
                || Math.abs(lat) > 90.0 || Math.abs(lon) > 180.0)) {
            throw ApiException.badRequest("INVALID_COORDINATE", "기준 좌표를 확인해 주세요.");
        }
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.length() > 100) {
            throw ApiException.badRequest("INVALID_QUERY", "검색어는 100자 이하로 입력해 주세요.");
        }
        if (trimmed.isEmpty()) {
            return List.of();
        }
        if (!enabled) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLACE_SEARCH_UNAVAILABLE", "장소 검색 키가 설정되지 않았어요.");
        }

        try {
            JsonNode root = client.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder.path("/v2/local/search/keyword.json")
                                .queryParam("query", trimmed)
                                .queryParam("size", 5);
                        if (lat != null) {
                            builder.queryParam("x", lon).queryParam("y", lat);
                        }
                        return builder.build();
                    })
                    .retrieve()
                    .body(JsonNode.class);
            if (root == null || !root.has("documents") || !root.get("documents").isArray()) {
                return List.of();
            }
            List<Place> results = new ArrayList<>();
            for (JsonNode doc : root.get("documents")) {
                String road = doc.path("road_address_name").asText("").trim();
                String address = doc.path("address_name").asText("").trim();
                results.add(new Place(
                        doc.path("id").asText(""),
                        doc.path("place_name").asText(""),
                        road.isEmpty() ? address : road,
                        Double.parseDouble(doc.path("y").asText()),
                        Double.parseDouble(doc.path("x").asText())));
            }
            return results;
        } catch (RestClientException | NumberFormatException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLACE_SEARCH_FAILED", "장소 검색에 실패했어요.");
        }
    }
}
