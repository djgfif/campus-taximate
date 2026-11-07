package com.campustaximate.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.TripPath;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.route.RouteClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SoloRouteLookupTest {

    private final RouteClient routes = mock(RouteClient.class);
    private final AppClock clock = new AppClock();
    private final SoloRouteLookup lookup = new SoloRouteLookup(routes, clock);

    private static TripPath path(int i) {
        return new TripPath(new GeoPoint(37, 127), List.of(new GeoPoint(37 + i * 0.01, 127)));
    }

    private void stub() {
        when(routes.routeAt(any(), any(), any())).thenReturn(new RouteClient.Route(5, 600, 9000));
    }

    @Test
    void sameLookupCallsRouteOnceUntilItExpires() {
        stub();
        lookup.preview(1, path(1), null);
        lookup.route(path(1), null);
        verify(routes, times(1)).routeAt(any(), any(), any());

        clock.moveTo(Instant.now().plusSeconds(601));
        lookup.route(path(1), null);
        verify(routes, times(2)).routeAt(any(), any(), any());
    }

    @Test
    void previewsOverTheLimitAreRefusedPerMember() {
        stub();
        for (int i = 0; i < SoloRouteLookup.PREVIEW_LIMIT_PER_MINUTE; i++) {
            lookup.preview(1, path(i), null);
        }
        assertThatThrownBy(() -> lookup.preview(1, path(99), null)).isInstanceOf(ApiException.class);
        lookup.preview(2, path(99), null);
        lookup.preview(1, path(0), null);

        clock.moveTo(Instant.now().plusSeconds(61));
        lookup.preview(1, path(98), null);
        assertThat(lookup).isNotNull();
    }

    @Test
    void failedLookupIsNotKept() {
        when(routes.routeAt(any(), any(), any()))
                .thenThrow(new RouteClient.RouteUnavailableException("x", null))
                .thenReturn(new RouteClient.Route(5, 600, 9000));
        assertThatThrownBy(() -> lookup.route(path(1), null)).isInstanceOf(RouteClient.RouteUnavailableException.class);
        assertThat(lookup.route(path(1), null).fare()).isEqualTo(9000);
    }
}
