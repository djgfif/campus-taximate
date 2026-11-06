package com.campustaximate.server.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 문서 형식 기준의 파싱 테스트다. 실제 응답 검증이 아니다. */
class KakaoRouteClientTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void readsDistanceDurationAndTaxiFare() throws Exception {
        var body = json.readTree("""
                {"routes":[{"result_code":0,"summary":{"distance":12500,"duration":1830,"fare":{"taxi":15000,"toll":1800}}}]}
                """);

        RouteClient.Route route = KakaoRouteClient.parse(body);

        assertThat(route.km()).isEqualTo(12.5);
        assertThat(route.seconds()).isEqualTo(1830);
        assertThat(route.fare()).isEqualTo(16800);
    }

    @Test
    void readsRoadVertexesAndUsesSummaryDuration() throws Exception {
        var body = json.readTree("""
                {"routes":[{"result_code":0,"summary":{"distance":1000,"duration":900,"fare":{"taxi":7000}},
                 "sections":[{"duration":1,"roads":[{"vertexes":[127.1,37.1,127.2,37.2]}]},
                             {"duration":2,"roads":[{"vertexes":[127.3,37.3]}]}]}]}
                """);
        var route = KakaoRouteClient.parse(body);
        assertThat(route.seconds()).isEqualTo(900);
        assertThat(route.coordinates()).containsExactly(new com.campustaximate.matching.GeoPoint(37.1, 127.1),
                new com.campustaximate.matching.GeoPoint(37.2, 127.2), new com.campustaximate.matching.GeoPoint(37.3, 127.3));
    }

    @Test
    void readsMultiDestinationLegsAndSkipsFailedOnes() throws Exception {
        var body = json.readTree("""
                {"routes":[{"result_code":0,"key":"7","summary":{"distance":4200,"duration":600}},
                           {"result_code":104,"key":"8"}]}
                """);

        var legs = KakaoRouteClient.parseLegs(body);

        assertThat(legs).containsOnlyKeys("7");
        assertThat(legs.get("7").km()).isEqualTo(4.2);
        assertThat(legs.get("7").seconds()).isEqualTo(600);
    }

    @Test
    void nearbyStopsInARowBecomeOneStop() {
        var a = new com.campustaximate.matching.GeoPoint(37.0, 127.0);
        var near = new com.campustaximate.matching.GeoPoint(37.0003, 127.0);
        var far = new com.campustaximate.matching.GeoPoint(37.002, 127.0);
        assertThat(KakaoRouteClient.mergeStops(java.util.List.of(a, a, near, far))).containsExactly(a, far);
    }

    @Test
    void slowKakaoResponseBecomesUnavailable() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        try {
            var client = new KakaoRouteClient(org.springframework.web.client.RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "key", java.time.Duration.ofMillis(200));
            var hub = new com.campustaximate.matching.GeoPoint(37.0, 127.0);
            var stop = new com.campustaximate.matching.GeoPoint(37.01, 127.0);
            assertThatThrownBy(() -> client.route(hub, java.util.List.of(stop)))
                    .isInstanceOf(RouteClient.RouteUnavailableException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedRouteIsUnavailable() throws Exception {
        var body = json.readTree("{\"routes\":[{\"result_code\":104,\"result_msg\":\"no route\"}]}");

        assertThatThrownBy(() -> KakaoRouteClient.parse(body))
                .isInstanceOf(RouteClient.RouteUnavailableException.class);
    }

    @Test
    void missingFareIsUnavailable() throws Exception {
        var body = json.readTree("{\"routes\":[{\"result_code\":0,\"summary\":{\"distance\":100,\"duration\":60}}]}");

        assertThatThrownBy(() -> KakaoRouteClient.parse(body))
                .isInstanceOf(RouteClient.RouteUnavailableException.class);
    }
}
