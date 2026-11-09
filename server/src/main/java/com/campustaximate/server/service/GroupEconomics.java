package com.campustaximate.server.service;

import com.campustaximate.matching.MatchingSettings;
import com.campustaximate.server.route.RouteClient;
import java.util.List;

/**
 * Both initial matching and pre-departure group changes must honor the same
 * fare-saving and additional-ride-time constraints.
 */
final class GroupEconomics {

    private static final MatchingSettings SETTINGS = MatchingSettings.defaults();

    private GroupEconomics() {
    }

    static boolean passes(long sharedFare, List<RouteClient.Route> solo, List<Integer> rideSeconds) {
        if (sharedFare <= 0 || solo == null || rideSeconds == null
                || solo.size() < 2 || solo.size() > SETTINGS.maxGroupSize()
                || solo.size() != rideSeconds.size()) {
            return false;
        }

        long soloFareTotal = 0;
        for (int i = 0; i < solo.size(); i++) {
            RouteClient.Route route = solo.get(i);
            Integer sharedSeconds = rideSeconds.get(i);
            if (route == null || route.fare() <= 0 || route.seconds() < 0
                    || sharedSeconds == null || sharedSeconds < 0
                    || (long) sharedSeconds - route.seconds() > SETTINGS.maxDelayMinutes() * 60L) {
                return false;
            }
            soloFareTotal = Math.addExact(soloFareTotal, route.fare());
        }
        return sharedFare <= soloFareTotal * (1 - SETTINGS.minSavingRate());
    }
}
