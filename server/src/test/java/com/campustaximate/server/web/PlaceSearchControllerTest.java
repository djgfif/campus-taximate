package com.campustaximate.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.campustaximate.server.common.ApiException;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PlaceSearchControllerTest {

    private static MockHttpSession signedInSession() {
        var session = new MockHttpSession();
        session.setAttribute(LoginTokenFilter.SESSION_KEY, 1L);
        return session;
    }

    @Test
    void searchesKakaoLocalWithRestKeyAndMapsRoadOrJibunAddress() {
        var builder = RestClient.builder()
                .baseUrl("https://dapi.kakao.test")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK rest-secret");
        var server = MockRestServiceServer.bindTo(builder).build();
        var controller = new PlaceSearchController(builder.build(), true);

        server.expect(requestTo(Matchers.startsWith("https://dapi.kakao.test/v2/local/search/keyword.json")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "KakaoAK rest-secret"))
                .andExpect(queryParam("size", "5"))
                .andExpect(queryParam("x", "127.155"))
                .andExpect(queryParam("y", "37.875"))
                .andRespond(withSuccess("""
                        {
                          "documents": [
                            {
                              "id": "8122492",
                              "place_name": "포천시청",
                              "road_address_name": "경기 포천시 중앙로 87",
                              "address_name": "경기 포천시 신읍동 43-1",
                              "x": "127.2003",
                              "y": "37.8949"
                            },
                            {
                              "id": "99",
                              "place_name": "정류장",
                              "road_address_name": "",
                              "address_name": "경기 포천시 선단동",
                              "x": "127.1561",
                              "y": "37.8707"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<PlaceSearchController.Place> results = controller.search("포천시청", 37.875, 127.155, signedInSession());

        server.verify();
        assertThat(results).containsExactly(
                new PlaceSearchController.Place("8122492", "포천시청", "경기 포천시 중앙로 87", 37.8949, 127.2003),
                new PlaceSearchController.Place("99", "정류장", "경기 포천시 선단동", 37.8707, 127.1561));
    }

    @Test
    void returnsEmptyWithoutCallingUpstreamWhenQueryBlank() {
        var builder = RestClient.builder().baseUrl("https://dapi.kakao.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var controller = new PlaceSearchController(builder.build(), true);

        assertThat(controller.search("   ", null, null, signedInSession())).isEmpty();
        server.verify();
    }

    @Test
    void throwsServiceUnavailableWhenRestKeyIsUnset() {
        var controller = new PlaceSearchController(RestClient.builder().build(), false);

        assertThatThrownBy(() -> controller.search("포천시청", null, null, signedInSession()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    var api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(api.getCode()).isEqualTo("PLACE_SEARCH_UNAVAILABLE");
                });
    }

    @Test
    void rejectsPartialOrOutOfRangeCoordinates() {
        var controller = new PlaceSearchController(RestClient.builder().build(), true);

        assertThatThrownBy(() -> controller.search("포천시청", 37.8, null, signedInSession()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("INVALID_COORDINATE");
        assertThatThrownBy(() -> controller.search("포천시청", 91.0, 127.1, signedInSession()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("INVALID_COORDINATE");
    }

    @Test
    void rejectsAnonymousRequest() {
        var controller = new PlaceSearchController(RestClient.builder().build(), true);

        assertThatThrownBy(() -> controller.search("포천시청", null, null, new MockHttpSession()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
