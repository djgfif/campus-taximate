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
