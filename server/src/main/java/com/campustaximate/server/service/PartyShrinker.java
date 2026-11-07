package com.campustaximate.server.service;

import com.campustaximate.matching.Direction;
import com.campustaximate.matching.GeoPoint;
import com.campustaximate.matching.TripPath;
import com.campustaximate.matching.TripPlan;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.common.ApiException;
import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.RequestStatus;
import com.campustaximate.server.domain.RideRequest;
import com.campustaximate.server.repository.PartyRepository;
import com.campustaximate.server.repository.RideRequestRepository;
import com.campustaximate.server.route.RouteClient;
import com.campustaximate.settlement.SettlementCalculator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 빠진 사람만 빼고 남은 사람으로 일행을 다시 계산한다. 새 사람을 찾지 않는다.
 * 1명만 남거나 다시 조회하지 못하면 일행을 취소한다.
 */
@Component
@RequiredArgsConstructor
public class PartyShrinker {

    private final PartyRepository parties;
    private final RideRequestRepository requests;
    private final PartyCanceler canceler;
    private final RouteClient routes;
    private final RoutePathStore paths;
    private final MailService mail;
    private final PlatformTransactionManager transactionManager;
    private final AppClock clock;

    private record Stay(long id, GeoPoint own, Instant boardAt, int rideSeconds, double soloKm, int soloSeconds,
                        long soloFare) {
    }

    private record Plan(long version, Direction direction, GeoPoint anchor, long total, List<Stay> stays) {
    }

    /** departed이면 남은 사람은 탑승 완료가 된다. */
    public void remove(long partyId, Set<Long> requested, Instant now, boolean departed) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Set<Long> leaving = tx.execute(status -> withNoShows(partyId, requested));
        Plan plan = tx.execute(status -> plan(partyId, leaving));
        if (plan.stays().size() < 2) {
            cancel(tx, partyId, leaving, now, departed);
            return;
        }
        Recalculated result = recalculateOrKeep(plan, departed);
        if (result == null) {
            cancel(tx, partyId, leaving, now, departed);
            return;
        }
        tx.executeWithoutResult(status -> apply(partyId, leaving, plan, result, departed));
    }

    /** 이미 택시를 탄 일행은 조회가 실패해도 취소하지 않는다. 취소하면 결제자가 낸 돈을 나눌 길이 없어진다. */
    private Recalculated recalculateOrKeep(Plan plan, boolean departed) {
        try {
            return recalculate(plan);
        } catch (RouteClient.RouteUnavailableException e) {
            return departed ? keepStoredValues(plan) : null;
        } catch (IllegalArgumentException | SettlementCalculator.NegativeShareException e) {
            return null;
        }
    }

    /** 저장된 단독 값과 예상 요금을 그대로 쓴다. 실제 금액은 결제 뒤 같은 비율로 나눈다. */
    private Recalculated keepStoredValues(Plan plan) {
        TripPlan trip = TripPlan.of(plan.direction());
        List<RouteClient.Route> solo = plan.stays().stream()
                .map(stay -> new RouteClient.Route(stay.soloKm(), stay.soloSeconds(), stay.soloFare()))
                .toList();
        List<Long> units = SettlementCalculator.ratioUnits(solo.stream().map(RouteClient.Route::fare).toList());
        try {
            return new Recalculated(plan.stays().stream().map(Stay::id).toList(), solo,
                    plan.stays().stream().map(Stay::rideSeconds).toList(), plan.total(),
                    SettlementCalculator.shares(plan.total(), units, trip.payerIndex(plan.stays().size())));
        } catch (IllegalArgumentException | SettlementCalculator.NegativeShareException e) {
            return null;
        }
    }

    /** 이미 노쇼로 적힌 사람은 함께 뺀다. 남겨 두면 재계산이 그 사람을 결제자로 되돌릴 수 있다. */
    private Set<Long> withNoShows(long partyId, Set<Long> requested) {
        Set<Long> leaving = new HashSet<>(requested);
        Party party = parties.findById(partyId).orElseThrow();
        requests.findByPartyOrderByRideOrder(party).stream()
                .filter(RideRequest::isNoShow)
                .forEach(rider -> leaving.add(rider.getId()));
        return leaving;
    }

    private Plan plan(long partyId, Set<Long> leaving) {
        Party party = parties.findById(partyId).orElseThrow();
        List<Stay> stays = new ArrayList<>();
        for (RideRequest rider : requests.findByPartyOrderByRideOrder(party)) {
            if (!leaving.contains(rider.getId())) {
                stays.add(new Stay(rider.getId(), rider.point(), rider.getBoardAt(), rider.getSharedSeconds(),
                        rider.getSoloKm(), rider.getSoloSeconds(), rider.getSoloFare()));
            }
        }
        return new Plan(party.getVersion(), party.getDirection(), party.anchorPoint(), party.getExpectedTotal(), stays);
    }

    private record Recalculated(List<Long> ids, List<RouteClient.Route> solo, List<Integer> rideSeconds,
                                long total, List<Long> shares) {
    }

    private Recalculated recalculate(Plan plan) {
        TripPlan trip = TripPlan.of(plan.direction());
        List<RouteClient.Route> alone = new ArrayList<>();
        for (Stay stay : plan.stays()) {
            TripPath path = trip.soloPath(plan.anchor(), stay.own());
            RouteClient.Route route = routes.routeAt(path.origin(), path.stops(), stay.boardAt());
            paths.remember(path.origin(), path.stops(), route);
            alone.add(route);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < alone.size(); i++) {
            order.add(i);
        }
        if (trip.reordersOnShrink()) {
            order.sort(java.util.Comparator.<Integer>comparingDouble(i -> alone.get(i).km())
                    .thenComparingLong(i -> plan.stays().get(i).id()));
        }
        List<RouteClient.Route> solo = order.stream().map(alone::get).toList();
        List<Stay> stays = order.stream().map(plan.stays()::get).toList();
        List<Integer> rides = new ArrayList<>();
        long total = trip.reordersOnShrink()
                ? cumulativeRides(plan.anchor(), stays, solo, rides)
                : fixedRides(trip, plan.anchor(), stays, rides);
        List<RouteClient.Route> shown = new ArrayList<>();
        for (int i = 0; i < solo.size(); i++) {
            shown.add(solo.get(i).withFare(stays.get(i).soloFare()));
        }
        List<Long> units = SettlementCalculator.ratioUnits(shown.stream().map(RouteClient.Route::fare).toList());
        return new Recalculated(stays.stream().map(Stay::id).toList(), shown, rides, total,
                SettlementCalculator.shares(total, units, trip.payerIndex(stays.size())));
    }

    /** 하교. 거점에서 앞사람 지점을 차례로 거친 누적 경로로 각자 시간을 구한다. */
    private long cumulativeRides(GeoPoint hub, List<Stay> stays, List<RouteClient.Route> solo, List<Integer> rides) {
        List<GeoPoint> stops = new ArrayList<>();
        RouteClient.Route last = null;
        for (int i = 0; i < stays.size(); i++) {
            boolean sameStop = !stops.isEmpty() && stops.getLast().isSameStop(stays.get(i).own());
            stops.add(stays.get(i).own());
            RouteClient.Route route = stops.size() == 1 ? solo.get(i)
                    : sameStop ? last : routes.route(hub, List.copyOf(stops));
            paths.remember(hub, stops, route);
            rides.add(route.seconds());
            last = route;
        }
        return last.fare();
    }

    /** 등교. 승차 시각을 앞당기지 않으므로 탑승 시간은 그대로 두고 전체 요금만 다시 구한다. */
    private long fixedRides(TripPlan trip, GeoPoint school, List<Stay> stays, List<Integer> rides) {
        TripPath path = trip.sharedPath(school, stays.stream().map(Stay::own).toList()).merged();
        RouteClient.Route route = routes.routeAt(path.origin(), path.stops(), stays.get(0).boardAt());
        paths.remember(path.origin(), path.stops(), route);
        stays.forEach(stay -> rides.add(stay.rideSeconds()));
        return route.fare();
    }

    private void cancel(TransactionTemplate tx, long partyId, Set<Long> leaving, Instant now, boolean departed) {
        tx.executeWithoutResult(status -> {
            Party party = parties.findByIdForUpdate(partyId).orElseThrow();
            requireOpen(party, leaving, departed);
            canceler.cancel(party, leaving, now);
        });
    }

    /** 경로를 조회하는 동안 출발 처리가 끝났거나 빠질 사람의 탑승 시각이 지났으면 바꾸지 않는다. */
    private void requireOpen(Party party, Set<Long> leaving, boolean departed) {
        List<RideRequest> riders = requests.findByPartyOrderByRideOrder(party);
        boolean closed = riders.stream().anyMatch(rider -> rider.getStatus() != RequestStatus.MATCHED);
        boolean tooLate = !departed && riders.stream()
                .filter(rider -> leaving.contains(rider.getId()) && !rider.isNoShow())
                .anyMatch(rider -> !rider.canCancelAt(clock.now()));
        if (closed || tooLate) {
            throw ApiException.conflict("DEPARTED", "출발 시각이 지나 취소할 수 없습니다.");
        }
    }

    private void apply(long partyId, Set<Long> leaving, Plan plan, Recalculated result, boolean departed) {
        Party party = parties.findByIdForUpdate(partyId).orElseThrow();
        if (party.getVersion() != plan.version()) {
            throw ApiException.conflict("CONFLICT", "일행이 바뀌었습니다. 다시 시도해 주세요.");
        }
        requireOpen(party, leaving, departed);
        List<RideRequest> stay = new ArrayList<>();
        for (RideRequest rider : requests.findByPartyOrderByRideOrder(party)) {
            if (leaving.contains(rider.getId())) {
                rider.clearParty();
                rider.finish(RequestStatus.CANCELED);
            } else {
                stay.add(rider);
            }
        }
        if (!new HashSet<>(result.ids()).equals(new HashSet<>(stay.stream().map(RideRequest::getId).toList()))) {
            throw ApiException.conflict("CONFLICT", "일행이 바뀌었습니다. 다시 시도해 주세요.");
        }
        stay.sort(java.util.Comparator.comparingInt(r -> result.ids().indexOf(r.getId())));
        TripPlan trip = TripPlan.of(party.getDirection());
        List<Long> units = SettlementCalculator.ratioUnits(result.solo().stream().map(RouteClient.Route::fare).toList());
        List<Long> shares = result.shares();
        for (int i = 0; i < stay.size(); i++) {
            RideRequest rider = stay.get(i);
            rider.setSoloKm(result.solo().get(i).km());
            rider.setSoloSeconds(result.solo().get(i).seconds());
            rider.setSoloFare(result.solo().get(i).fare());
            rider.setSharedSeconds(result.rideSeconds().get(i));
            rider.setRideOrder(i + 1);
            rider.setRatioUnits(units.get(i));
            rider.setExpectedShare(shares.get(i));
            if (departed) {
                rider.finish(RequestStatus.COMPLETED);
            }
            mail.send(rider.getMember().getEmail(), "[캠퍼스 택시메이트] 일행 변경",
                    "일행 중 한 명이 빠져 분담금과 결제자가 다시 계산됐습니다. 앱에서 확인해 주세요.");
        }
        party.setExpectedTotal(result.total());
        party.setPayer(stay.get(trip.payerIndex(stay.size())).getMember());
        if (stay.get(0).getBoardAt() != null) {
            party.setDepartureAt(stay.get(0).getBoardAt());
        }
    }
}
