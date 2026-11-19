package com.campustaximate.server.service;
import static org.assertj.core.api.Assertions.*;import static org.mockito.ArgumentMatchers.*;import static org.mockito.Mockito.*;
import com.campustaximate.matching.*;import com.campustaximate.server.route.RouteClient;
import java.time.Instant;import java.util.*;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.extension.ExtendWith;import org.springframework.boot.test.system.*;
@ExtendWith(OutputCaptureExtension.class)
class MatchingDiagnosticsTest {
    @Test void delayAndBlockedDiagnosticsContainOnlyIdsAndNumbers(CapturedOutput out){
        Instant slot=Instant.parse("2026-11-12T00:00:00Z");Hub hub=new Hub(1,new GeoPoint(37,127));
        var a=new MatchRequest(1,1,hub,Gender.FEMALE,slot.plusSeconds(1800),slot.plusSeconds(3600),new GeoPoint(37.01,127),1,100).requested(slot.minusSeconds(600),false);
        var b=new MatchRequest(2,2,hub,Gender.FEMALE,slot.plusSeconds(1800),slot.plusSeconds(3600),new GeoPoint(37.02,127),2,100).requested(slot.minusSeconds(600),false);
        var diagnostics=new MatchingDiagnostics(MatchingSettings.everyMinute());diagnostics.inspect(slot,List.of(a,b),(x,y)->Optional.of(new RoadLeg(1,1000)));
        diagnostics.inspect(slot,List.of(a.withAvoidMemberIds(Set.of(2L)),b),(x,y)->Optional.of(new RoadLeg(1,10)));
        assertThat(out.getAll()).contains("code=DELAY","code=BLOCKED","requests=[1, 2]").doesNotContain("GeoPoint","lat=","37.01");
    }
    @Test void secondarySavingsAndUnavailabilityHaveDistinctCodes(CapturedOutput out){
        Instant slot=Instant.parse("2026-11-12T00:00:00Z");var hub=new Hub(1,new GeoPoint(37,127));
        var a=new MatchRequest(1,1,hub,Gender.FEMALE,slot,slot.plusSeconds(3600),new GeoPoint(37.01,127),1,600);
        var b=new MatchRequest(2,2,hub,Gender.FEMALE,slot,slot.plusSeconds(3600),new GeoPoint(37.02,127),2,600);
        var candidate=new Candidate(List.of(a,b),3,slot.plusSeconds(1500),List.of(600,1200));var routes=mock(RouteClient.class);
        doAnswer(c->{List<GeoPoint> stops=c.getArgument(1);return new RouteClient.Route(3,stops.size()*600,stops.size()==1?10000:19000,List.of(),Collections.nCopies(stops.size(),600));}).when(routes).routeAt(any(),anyList(),any());
        assertThat(new RouteGroupVerifier(routes,new HashMap<>(),slot).check(candidate).verdict()).isEqualTo(Verdict.FAIL);
        doThrow(new RouteClient.RouteUnavailableException("모의 시간 초과",null)).when(routes).routeAt(any(),anyList(),any());
        assertThat(new RouteGroupVerifier(routes,new HashMap<>(),slot).check(candidate).verdict()).isEqualTo(Verdict.UNAVAILABLE);
        assertThat(out.getAll()).contains("code=SAVINGS","code=SECONDARY_UNAVAILABLE");
    }
}
