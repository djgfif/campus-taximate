package com.campustaximate.server.service;

import com.campustaximate.matching.GeoPoint;
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

    private record Plan(long version, GeoPoint hub, List<GeoPoint> stops, List<Long> ids) {
    }

    /** departed이면 남은 사람은 탑승 완료가 된다. */
    public void remove(long partyId, Set<Long> leaving, Instant now, boolean departed) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Plan plan = tx.execute(status -> plan(partyId, leaving));
        if (plan.stops().size() < 2) {
            cancel(tx, partyId, leaving, now);
            return;
        }
        Recalculated result;
        try {
            result = recalculate(plan);
        } catch (RouteClient.RouteUnavailableException | IllegalArgumentException
                 | SettlementCalculator.NegativeShareException e) {
            cancel(tx, partyId, leaving, now);
            return;
        }
        tx.executeWithoutResult(status -> apply(partyId, leaving, plan, result, departed));
    }

    private Plan plan(long partyId, Set<Long> leaving) {
        Party party = parties.findById(partyId).orElseThrow();
        List<GeoPoint> stops = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (RideRequest rider : requests.findByPartyOrderByDropoffOrder(party)) {
            if (!leaving.contains(rider.getId())) {
                ids.add(rider.getId());
                stops.add(new GeoPoint(rider.getDropoffLat(), rider.getDropoffLon()));
            }
        }
        return new Plan(party.getVersion(), new GeoPoint(party.getHub().getLat(), party.getHub().getLon()), stops, ids);
    }

    private record Recalculated(List<Long> ids, List<RouteClient.Route> solo,
                                List<RouteClient.Route> cumulative, List<Long> shares) {
    }

    private Recalculated recalculate(Plan plan) {
        List<RouteClient.Route> alone = new ArrayList<>();
        for (GeoPoint stop : plan.stops()) {
            RouteClient.Route route = routes.route(plan.hub(), List.of(stop));
            paths.remember(plan.hub(), List.of(stop), route);
            alone.add(route);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < alone.size(); i++) {
            order.add(i);
        }
        order.sort(java.util.Comparator.<Integer>comparingDouble(i -> alone.get(i).km())
                .thenComparingLong(i -> plan.ids().get(i)));
        List<GeoPoint> stops = new ArrayList<>();
        List<RouteClient.Route> solo = new ArrayList<>();
        List<RouteClient.Route> cumulative = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (int i : order) {
            stops.add(plan.stops().get(i));
            RouteClient.Route route = stops.size() == 1 ? alone.get(i) : routes.route(plan.hub(), List.copyOf(stops));
            paths.remember(plan.hub(), stops, route);
            cumulative.add(route);
            solo.add(alone.get(i));
            ids.add(plan.ids().get(i));
        }
        List<Long> units = SettlementCalculator.ratioUnits(solo.stream().map(RouteClient.Route::fare).toList());
        return new Recalculated(ids, solo, cumulative,
                SettlementCalculator.shares(cumulative.getLast().fare(), units));
    }

    private void cancel(TransactionTemplate tx, long partyId, Set<Long> leaving, Instant now) {
        tx.executeWithoutResult(status ->
                canceler.cancel(parties.findByIdForUpdate(partyId).orElseThrow(), leaving, now));
    }

    private void apply(long partyId, Set<Long> leaving, Plan plan, Recalculated result, boolean departed) {
        Party party = parties.findByIdForUpdate(partyId).orElseThrow();
        if (party.getVersion() != plan.version()) {
            throw ApiException.conflict("CONFLICT", "일행이 바뀌었습니다. 다시 시도해 주세요.");
        }
        List<RideRequest> stay = new ArrayList<>();
        for (RideRequest rider : requests.findByPartyOrderByDropoffOrder(party)) {
            if (leaving.contains(rider.getId())) {
                rider.clearParty();
                rider.finish(RequestStatus.CANCELED);
            } else {
                stay.add(rider);
            }
        }
        stay.sort(java.util.Comparator.comparingInt(r -> result.ids().indexOf(r.getId())));
        List<Long> units = SettlementCalculator.ratioUnits(result.solo().stream().map(RouteClient.Route::fare).toList());
        long total = result.cumulative().getLast().fare();
        List<Long> shares = result.shares();
        for (int i = 0; i < stay.size(); i++) {
            RideRequest rider = stay.get(i);
            rider.setSoloKm(result.solo().get(i).km());
            rider.setSoloSeconds(result.solo().get(i).seconds());
            rider.setSoloFare(result.solo().get(i).fare());
            rider.setSharedSeconds(result.cumulative().get(i).seconds());
            rider.setDropoffOrder(i + 1);
            rider.setRatioUnits(units.get(i));
            rider.setExpectedShare(shares.get(i));
            if (departed) {
                rider.finish(RequestStatus.COMPLETED);
            }
            mail.send(rider.getMember().getEmail(), "[캠퍼스 택시메이트] 일행 변경",
                    "일행 중 한 명이 빠져 분담금과 결제자가 다시 계산됐습니다. 앱에서 확인해 주세요.");
        }
        party.setExpectedTotal(total);
        party.setPayer(stay.get(stay.size() - 1).getMember());
    }
}
