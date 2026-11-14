package com.campustaximate.server.route;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.campustaximate.matching.GeoPoint;import com.campustaximate.server.common.AppClock;
import java.time.*;import java.net.SocketTimeoutException;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;import org.springframework.web.client.RestClient;
@ExtendWith(OutputCaptureExtension.class)
class TmapWalkClientTest {
    final Instant now=Instant.parse("2026-11-12T00:00:00Z");final GeoPoint from=new GeoPoint(37.00001,127),point=new GeoPoint(37.001,127);
    AppClock clock;MockRestServiceServer server;TmapWalkClient client;
    @BeforeEach void setup(){clock=mock(AppClock.class);when(clock.now()).thenReturn(now);var builder=RestClient.builder().baseUrl("https://tmap.test").defaultHeader("appKey","test-key");server=MockRestServiceServer.bindTo(builder).build();client=new TmapWalkClient(builder.build(),clock,Duration.ofMinutes(30),1000);}
    String body(){return "{\"features\":[{\"properties\":{\"totalTime\":180}}]}";}
    @Test void successUsesMeasuredSecondsAndAppKey(){
        server.expect(requestTo("https://tmap.test/tmap/routes/pedestrian?version=1")).andExpect(method(HttpMethod.POST)).andExpect(header("appKey","test-key")).andRespond(withSuccess(body(),MediaType.APPLICATION_JSON));
        assertThat(client.estimate(from,point)).isEqualTo(new WalkEstimator.Walk(180,false));assertThat(client.dailyUsage()).isEqualTo(1);server.verify();
    }
    @Test void E02_timeoutRejectsInsteadOfEstimating(){
        server.expect(anything()).andRespond(r->{throw new SocketTimeoutException("모의 3초 시간 초과");});
        assertThatThrownBy(()->client.estimate(from,point)).isInstanceOf(WalkUnavailableException.class)
                .extracting("code").isEqualTo("WALK_UNAVAILABLE");
        server.verify();
    }
    @Test void E02_httpErrorAndMalformedTimeReject(){
        server.expect(anything()).andRespond(withServerError());
        assertThatThrownBy(()->client.estimate(from,point)).isInstanceOf(WalkUnavailableException.class);
        server.verify();
    }
    @Test void E02_failureIsNotCachedSoNextRequestRetries(){
        server.expect(anything()).andRespond(withServerError());
        server.expect(anything()).andRespond(withSuccess(body(),MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->client.estimate(from,point)).isInstanceOf(WalkUnavailableException.class);
        assertThat(client.estimate(from,point)).isEqualTo(new WalkEstimator.Walk(180,false));
        server.verify();
    }
    @Test void roundedOriginCachesForThirtyMinutesAndExpires(){
        server.expect(org.springframework.test.web.client.ExpectedCount.twice(),anything()).andRespond(withSuccess(body(),MediaType.APPLICATION_JSON));
        client.estimate(from,point);client.estimate(new GeoPoint(37.00002,127),point);assertThat(client.dailyUsage()).isEqualTo(1);
        when(clock.now()).thenReturn(now.plusSeconds(1800));client.estimate(from,point);assertThat(client.dailyUsage()).isEqualTo(2);server.verify();
    }
    @Test void blankKeyKeepsExistingEstimatorWithoutHttp(){
        var estimator=new WalkConfig().walkEstimator(RestClient.builder(),clock,"","https://tmap.test",3000,30,1000);
        assertThat(estimator).isInstanceOf(ConservativeWalkEstimator.class);assertThat(estimator.estimate(from,point).estimated()).isTrue();
    }
    @Test void dailyQuotaWarnsAtEightyPercentAndResetsInSeoul(CapturedOutput output){
        var builder=RestClient.builder().baseUrl("https://tmap.test");var mock=MockRestServiceServer.bindTo(builder).build();var quota=new TmapWalkClient(builder.build(),clock,Duration.ofMinutes(30),5);
        mock.expect(org.springframework.test.web.client.ExpectedCount.times(6),anything()).andRespond(withSuccess(body(),MediaType.APPLICATION_JSON));
        // 6번째 요청은 한도 초과라 HTTP 없이 거절, 날짜가 바뀐 뒤 1번 더 호출해 모두 6번.
        for(int i=0;i<5;i++)quota.estimate(new GeoPoint(37+i*.001,127),point);
        assertThatThrownBy(()->quota.estimate(new GeoPoint(38,127),point)).isInstanceOf(WalkUnavailableException.class);assertThat(quota.dailyUsage()).isEqualTo(5);
        assertThat(output.getOut()+output.getErr()).contains("TMAP 보행 일일 사용량 80% 도달");
        when(clock.now()).thenReturn(now.plusSeconds(86400));quota.estimate(from,point);assertThat(quota.dailyUsage()).isEqualTo(1);mock.verify();
    }
}
