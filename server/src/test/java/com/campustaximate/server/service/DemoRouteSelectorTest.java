package com.campustaximate.server.service;
import static org.assertj.core.api.Assertions.*;import static org.mockito.ArgumentMatchers.*;import static org.mockito.Mockito.*;
import com.campustaximate.matching.*;import com.campustaximate.server.common.AppClock;import com.campustaximate.server.route.RouteClient;
import java.time.Instant;import java.util.*;import org.junit.jupiter.api.Test;
class DemoRouteSelectorTest {
    @Test void realRoadProbeRejectsStraightPointsAndSelectsPassingRegisteredDropoff(){
        Instant now=Instant.parse("2026-11-12T00:00:00Z");var clock=mock(AppClock.class);when(clock.now()).thenReturn(now);var routes=mock(RouteClient.class);
        GeoPoint anchor=new GeoPoint(37.875,127.155),destination=new GeoPoint(37.9,127.15);
        var first=DataSeeder.SAMPLE_DROPOFFS.getFirst();GeoPoint good=new GeoPoint(first.lat(),first.lon());
        doAnswer(c->{GeoPoint origin=c.getArgument(0);List<GeoPoint> stops=c.getArgument(1);
            if(stops.size()==1)return new RouteClient.Route(stops.getFirst().equals(destination)?10:5,origin.equals(anchor)?600:300,10000,List.of(),List.of(origin.equals(anchor)?600:300));
            boolean pass=stops.contains(good);return new RouteClient.Route(11,pass?1200:3600,15000,List.of(),Collections.nCopies(stops.size(),pass?600:1800));
        }).when(routes).routeAt(any(),anyList(),any());
        MatchRequest mine=new MatchRequest(1,1,new Hub(1,anchor),Gender.FEMALE,now.plusSeconds(1800),now.plusSeconds(3600),destination,10,600).requested(now,false);
        var selected=new DemoRouteSelector(routes,MatchingSettings.everyMinute(),clock).select(mine,10000,anchor,List.of(2L));
        assertThat(selected).containsExactly(good);verify(routes,atLeast(2)).routeAt(eq(anchor),argThat(s->s.size()>1),any());
    }
    @Test void simulatedRoutesKeepExistingDemoBranch(){var routes=mock(RouteClient.class);when(routes.simulated()).thenReturn(true);assertThat(new DemoRouteSelector(routes,MatchingSettings.everyMinute(),mock(AppClock.class)).realRoutes()).isFalse();verify(routes,never()).routeAt(any(),anyList(),any());}
    @Test void passengerUsesExtensionWhenRegisteredStopsAreCloser() {
        Instant now=Instant.parse("2026-11-12T00:00:00Z");var clock=mock(AppClock.class);when(clock.now()).thenReturn(now);
        var routes=new com.campustaximate.server.route.SimulatedRouteClient();
        GeoPoint anchor=new GeoPoint(37.875,127.155),destination=new GeoPoint(37.70,127.10);
        var solo=routes.route(anchor,List.of(destination));
        MatchRequest mine=new MatchRequest(1,1,new Hub(1,anchor),Gender.FEMALE,now.plusSeconds(3600),now.plusSeconds(7200),destination,solo.km(),solo.seconds()).requested(now,false);
        var selected=new DemoRouteSelector(routes,MatchingSettings.everyMinute(),clock).select(mine,solo.fare(),anchor,List.of(2L,3L),true);
        assertThat(selected).hasSize(2);
        assertThat(anchor.distanceKm(selected.getFirst())).isGreaterThan(anchor.distanceKm(destination));
        assertThat(anchor.distanceKm(selected.get(1))).isLessThan(anchor.distanceKm(destination));
    }
    @Test void passengerDoesNotBypassFailedRoadVerification() {
        Instant now=Instant.parse("2026-11-12T00:00:00Z");var clock=mock(AppClock.class);when(clock.now()).thenReturn(now);
        var routes=mock(RouteClient.class);
        when(routes.routeAt(any(),anyList(),any())).thenThrow(new RouteClient.RouteUnavailableException("시연 장애",null));
        GeoPoint anchor=new GeoPoint(37.875,127.155);
        MatchRequest mine=new MatchRequest(1,1,new Hub(1,anchor),Gender.FEMALE,now.plusSeconds(3600),now.plusSeconds(7200),new GeoPoint(37.833,127.146),6,900).requested(now,false);
        assertThatThrownBy(()->new DemoRouteSelector(routes,MatchingSettings.everyMinute(),clock).select(mine,10000,anchor,List.of(2L),true))
                .isInstanceOf(com.campustaximate.server.common.ApiException.class);
    }
}
