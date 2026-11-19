package com.campustaximate.server.route;

import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.RoadLeg;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Slf4j
@Configuration
public class RouteConfig {

    @Bean
    RouteClient routeClient(RestClient.Builder builder,
                            @Value("${app.kakao.rest-key}") String restKey,
                            @Value("${app.kakao.base-url}") String baseUrl,
                            @Value("${app.demo.enabled}") boolean demo,
                            @Value("${app.kakao.timeout-ms:3000}") long timeoutMillis,
                            @Value("${app.kakao.retries:1}") int retries) {
        if (!restKey.isBlank()) {
            return new KakaoRouteClient(builder, baseUrl, restKey, java.time.Duration.ofMillis(timeoutMillis), retries);
        }
        if (demo) {
            log.warn("KAKAO_REST_KEY가 없어 모의 경로를 씁니다. 실제 요금이 아닙니다.");
            return new SimulatedRouteClient();
        }
        return new RouteClient() {
            @Override
            public Route route(GeoPoint origin, List<GeoPoint> stops) {
                throw new RouteUnavailableException("KAKAO_REST_KEY is not set", null);
            }

            @Override
            public Map<String, RoadLeg> legs(GeoPoint origin, Map<String, GeoPoint> destinations) {
                throw new RouteUnavailableException("KAKAO_REST_KEY is not set", null);
            }

            @Override
            public boolean simulated() {
                return false;
            }
        };
    }
}
