package com.campustaximate.server.service;

import com.campustaximate.matching.Candidate;
import com.campustaximate.matching.Direction;
import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.GroupVerifier;
import com.campustaximate.matching.MatchRequest;
import com.campustaximate.matching.ToSchoolPlan;
import com.campustaximate.matching.TripPath;
import com.campustaximate.matching.TripPlan;
import com.campustaximate.matching.Verdict;
import com.campustaximate.matching.Verification;
import com.campustaximate.matching.MatchingSettings;
import com.campustaximate.server.route.RouteClient;
import com.campustaximate.settlement.SettlementCalculator;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 뽑힌 조합의 누적 값과 각자의 단독 값을 같은 시점에 조회해 15분, 10% 조건을 다시 검사한다. 슬롯 한 번만 쓴다. */
final class RouteGroupVerifier implements GroupVerifier {

    private static final int MAX_DELAY_SECONDS = 15 * 60;
    private static final Duration REJECT_KEEP = Duration.ofMinutes(30);
    private static final int MAX_BACKTRACK_QUERIES = 3;

    private final RouteClient routes;
    private final Map<List<Long>, Instant> rejected;
    private final Instant now;
    private record RouteKey(long anchorId, List<Long> ids) { }
    private static RouteKey key(List<MatchRequest> order) {
        return new RouteKey(order.getFirst().anchorId(), order.stream().map(MatchRequest::id).toList());
    }
    private final Map<RouteKey, VerifiedRoutes> verified = new HashMap<>();
    private final Map<Long, GeoPoint> origins;
    private final Instant slot;
    private final MatchingSettings settings;
    private final RoutePathStore paths;
    private final Map<Long, Long> requestFares;

    /** rideSeconds는 각자 차 안에 있는 시간이다. departure는 검증한 출발 시각이고 boardAt은 순차 승차만 값이 있다. */
    record VerifiedRoutes(List<RouteClient.Route> solo, List<Integer> rideSeconds, long total,
                          Instant departure, List<Instant> boardAt) {
    }

    RouteGroupVerifier(RouteClient routes, Map<List<Long>, Instant> rejected, Instant now) {
        this(routes, rejected, now, null);
    }

    RouteGroupVerifier(RouteClient routes, Map<List<Long>, Instant> rejected, Instant now, RoutePathStore paths) {
        this(routes, rejected, now, paths, Map.of());
    }

    /** requestFares는 요청할 때 보여 준 단독 요금이다. 시간만 같은 시점으로 다시 조회한다. */
    RouteGroupVerifier(RouteClient routes, Map<List<Long>, Instant> rejected, Instant now, RoutePathStore paths,
                       Map<Long, Long> requestFares) {
        this(routes, rejected, now, paths, requestFares, Map.of(), minuteFloor(now));
    }

    RouteGroupVerifier(RouteClient routes, Map<List<Long>, Instant> rejected, Instant now, RoutePathStore paths,
                       Map<Long, Long> requestFares, Map<Long, GeoPoint> origins, Instant slot) {
        this(routes,rejected,now,paths,requestFares,origins,slot,MatchingSettings.everyMinute());
    }

    RouteGroupVerifier(RouteClient routes, Map<List<Long>,Instant> rejected, Instant now, RoutePathStore paths,
                       Map<Long,Long> requestFares, Map<Long,GeoPoint> origins, Instant slot,MatchingSettings settings) {
        this.settings=settings;
        this.origins = origins;
        this.slot = slot;
        this.requestFares = requestFares;
        this.paths = paths;
        this.routes = routes;
        this.rejected = rejected;
        this.now = now;
    }

    @Override
    public boolean recentlyRejected(Candidate candidate) {
        Instant until = rejected.get(candidate.sortedIds());
        return until != null && now.isBefore(until);
    }

    private Verdict reject(Candidate candidate) {
        rejected.put(candidate.sortedIds(), now.plus(REJECT_KEEP));
        return Verdict.FAIL;
    }

    /** 통과한 조합의 같은 시점 경로 값. */
    VerifiedRoutes routesOf(List<MatchRequest> order) {
        return verified.get(key(order));
    }

    @Override
    public Verdict verify(Candidate candidate) {
        return check(candidate).verdict();
    }

    @Override
    public Verification check(Candidate candidate) {
        if (candidate.order().getFirst().direction() == Direction.TO_SCHOOL) {
            Verdict verdict = verifyToSchool(candidate);
            return new Verification(verdict, verdict == Verdict.PASS ? routesOf(candidate.order()).departure() : null);
        }
        Verdict verdict = verifyFromHub(candidate);
        return new Verification(verdict, verdict == Verdict.PASS ? routesOf(candidate.order()).departure() : null);
    }

    /** 전체 경로 한 번의 구간 누적값으로 개인별 하차를 계산한다. */
    private Verdict verifyFromHub(Candidate candidate) {
        List<MatchRequest> order = candidate.order();
        MatchRequest first = order.getFirst();
        GeoPoint hub = first.anchor();
        List<GeoPoint> stops = GeoPoint.mergeStops(order.stream().map(MatchRequest::own).toList());
        Instant departure = candidate.departure();
        if (departure == null) departure = slot.plusSeconds(MatchingSettings.everyMinute().leadMinutes() * 60L);
        boolean instant=order.stream().anyMatch(MatchRequest::instant);
        Instant minimum=instant?TripPlan.of(Direction.FROM_HUB,settings).schedule(order,java.util.Collections.nCopies(order.size(),0),slot).departure():slot.plusSeconds(settings.leadMinutes()*60L);
        if(minimum==null || instant&&order.stream().anyMatch(MatchRequest::arrival)) return reject(candidate);
        List<Integer> seconds = new ArrayList<>();
        List<RouteClient.Route> solo = new ArrayList<>();
        RouteClient.Route full = null;
        boolean fits = false;
        try {
            for (int attempt = 0; attempt < MAX_BACKTRACK_QUERIES; attempt++) {
                if (departure.isBefore(minimum)) return reject(candidate);
                full = routes.routeAt(hub, stops, departure);
                List<Integer> legs = legSeconds(full, stops.size());
                if (legs == null || legs.stream().anyMatch(n -> n < 0)
                        || legs.stream().mapToLong(Integer::longValue).sum() != full.seconds()) return Verdict.UNAVAILABLE;
                seconds.clear();
                int stopIndex = 0;
                int elapsed = legs.getFirst();
                for (MatchRequest rider : order) {
                    while (!stops.get(stopIndex).isSameStop(rider.own())) {
                        elapsed += legs.get(++stopIndex);
                    }
                    seconds.add(elapsed);
                }
                if (first.arrival()) {
                    Instant target = order.stream().map(MatchRequest::arriveBy).min(Instant::compareTo).orElseThrow();
                    Instant revised = minuteFloor(target.minusSeconds(seconds.getLast()));
                    if (!revised.equals(departure)) {
                        departure = revised;
                        continue;
                    }
                    for (int i = 0; i < order.size(); i++) {
                        Instant drop = departure.plusSeconds(seconds.get(i));
                        MatchRequest rider = order.get(i);
                        if (drop.isBefore(rider.earliestArrival()) || drop.isAfter(rider.arriveBy())) return reject(candidate);
                    }
                } else if (order.stream().anyMatch(r -> r.arrival())) {
                    return reject(candidate);
                } else {
                    for (MatchRequest rider : order) if (!rider.instant() && !rider.canDepartAt(departure)) return reject(candidate);
                }
                fits = true;
                break;
            }
            if (!fits) return reject(candidate);
            if (paths != null) paths.remember(hub, stops, full);
            for (MatchRequest rider : order) {
                GeoPoint origin = origins.getOrDefault(rider.id(), hub);
                RouteClient.Route alone = routes.routeAt(origin, List.of(rider.own()), departure);
                solo.add(shownFare(rider, alone));
                if (paths != null) paths.remember(origin, List.of(rider.own()), alone);
            }
        } catch (RouteClient.RouteUnavailableException e) {
            return Verdict.UNAVAILABLE;
        }
        return finish(candidate, new VerifiedRoutes(List.copyOf(solo), List.copyOf(seconds), full.fare(), departure, null));
    }

    /**
     * 도착 희망 시각에서 출발을 역산한다. 전체 경로의 구간 시간으로 각자 승차 시각과 탑승 시간을 구하고,
     * 단독 값은 자기 승차 시각으로 조회한다. 호출은 역산 3회와 단독 인원수만큼이다.
     */
    private Verdict verifyToSchool(Candidate candidate) {
        List<MatchRequest> order = candidate.order();
        MatchRequest first = order.get(0);
        TripPlan plan = TripPlan.of(Direction.TO_SCHOOL);
        TripPath path = plan.sharedPath(first.anchor(), order.stream().map(MatchRequest::own).toList());
        List<GeoPoint> all = new ArrayList<>();
        all.add(path.origin());
        all.addAll(path.stops());
        List<GeoPoint> merged = GeoPoint.mergeStops(all);
        if (merged.size() < 2) {
            return reject(candidate);
        }
        int stopCount = merged.size() - 2;
        List<GeoPoint> mergedStops = merged.subList(1, merged.size());
        Instant departure = minuteFloor(first.arriveBy().minusSeconds(first.soloSeconds()));
        RouteClient.Route full = null;
        boolean fits = false;
        List<RouteClient.Route> solo = new ArrayList<>();
        List<Integer> rides = new ArrayList<>();
        List<Instant> boardAt = new ArrayList<>();
        try {
            for (int attempt = 0; attempt < MAX_BACKTRACK_QUERIES && !fits; attempt++) {
                full = routes.routeAt(merged.get(0), mergedStops, departure);
                Instant arrival = departure.plusSeconds(full.seconds() + (long) stopCount * ToSchoolPlan.STOP_SECONDS);
                fits = !arrival.isAfter(first.arriveBy());
                if (!fits) {
                    departure = minuteFloor(departure.minus(Duration.between(first.arriveBy(), arrival)));
                }
            }
            if (!fits || !departure.isAfter(now)) {
                return reject(candidate);
            }
            List<Integer> legs = legSeconds(full, merged.size() - 1);
            if (legs == null) {
                return Verdict.UNAVAILABLE;
            }
            if (paths != null) {
                paths.remember(path.origin(), path.stops(), full);
            }
            int index = 0;
            for (MatchRequest rider : order) {
                while (!merged.get(index).isSameStop(rider.own())) {
                    index++;
                }
                Instant board = departure.plusSeconds(secondsBefore(legs, index));
                TripPath alonePath = plan.soloPath(rider.anchor(), rider.own());
                RouteClient.Route alone = routes.routeAt(alonePath.origin(), alonePath.stops(), board);
                if (paths != null) {
                    paths.remember(alonePath.origin(), alonePath.stops(), alone);
                }
                boardAt.add(board);
                solo.add(shownFare(rider, alone));
                rides.add(index == merged.size() - 2 ? alone.seconds() : secondsAfter(legs, index));
            }
        } catch (RouteClient.RouteUnavailableException e) {
            return Verdict.UNAVAILABLE;
        }
        return finish(candidate, new VerifiedRoutes(solo, rides, full.fare(), departure, boardAt));
    }

    private RouteClient.Route shownFare(MatchRequest rider, RouteClient.Route alone) {
        Long fare = requestFares.get(rider.id());
        return fare == null ? alone : alone.withFare(fare);
    }

    /** 구간 시간이 없으면 한 구간뿐일 때만 전체 시간을 쓴다. */
    private static List<Integer> legSeconds(RouteClient.Route route, int expected) {
        if (route.legSeconds().size() == expected) {
            return route.legSeconds();
        }
        return expected == 1 ? List.of(route.seconds()) : null;
    }

    private static int secondsBefore(List<Integer> legs, int index) {
        int seconds = index * ToSchoolPlan.STOP_SECONDS;
        for (int i = 0; i < index; i++) {
            seconds += legs.get(i);
        }
        return seconds;
    }

    private static int secondsAfter(List<Integer> legs, int index) {
        int seconds = (legs.size() - 1 - index) * ToSchoolPlan.STOP_SECONDS;
        for (int i = index; i < legs.size(); i++) {
            seconds += legs.get(i);
        }
        return seconds;
    }

    private static Instant minuteFloor(Instant time) {
        return time.truncatedTo(ChronoUnit.MINUTES);
    }

    /** 두 방향 공통의 10%, 15분, 분담금 검사. */
    private Verdict finish(Candidate candidate, VerifiedRoutes routesOfGroup) {
        List<MatchRequest> order = candidate.order();
        List<RouteClient.Route> solo = routesOfGroup.solo();
        long soloTotal = solo.stream().mapToLong(RouteClient.Route::fare).sum();
        if (java.math.BigInteger.valueOf(routesOfGroup.total()).multiply(java.math.BigInteger.TEN)
                .compareTo(java.math.BigInteger.valueOf(soloTotal).multiply(java.math.BigInteger.valueOf(9))) > 0) {
            return reject(candidate);
        }
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).walkSeconds() + routesOfGroup.rideSeconds().get(i) - solo.get(i).seconds() > MAX_DELAY_SECONDS) {
                return reject(candidate);
            }
        }
        try {
            TripPlan plan = TripPlan.of(order.get(0).direction());
            SettlementCalculator.shares(routesOfGroup.total(),
                    SettlementCalculator.ratioUnits(solo.stream().map(RouteClient.Route::fare).toList()),
                    plan.payerIndex(order.size()));
        } catch (IllegalArgumentException e) {
            return reject(candidate);
        }
        verified.put(key(order), routesOfGroup);
        return Verdict.PASS;
    }
}
