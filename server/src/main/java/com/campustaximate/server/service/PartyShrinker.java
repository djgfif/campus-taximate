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
    private final MailService mail;
    private final PlatformTransactionManager transactionManager;

    private record Plan(long version, GeoPoint hub, List<GeoPoint> stops) {
    }

    /** departed이면 남은 사람은 탑승 완료가 된다. */
    public void remove(long partyId, Set<Long> leaving, Instant now, boolean departed) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Plan plan = tx.execute(status -> plan(partyId, leaving));
        if (plan.stops().size() < 2) {
            cancel(tx, partyId, leaving, now);
            return;
        }
        List<Long> fares;
        try {
            fares = cumulativeFares(plan);
        } catch (RouteClient.RouteUnavailableException e) {
            cancel(tx, partyId, leaving, now);
            return;
        }
        tx.executeWithoutResult(status -> apply(partyId, leaving, plan, fares, departed));
    }

    private Plan plan(long partyId, Set<Long> leaving) {
        Party party = parties.findById(partyId).orElseThrow();
        List<GeoPoint> stops = new ArrayList<>();
        for (RideRequest rider : requests.findByPartyOrderByDropoffOrder(party)) {
            if (!leaving.contains(rider.getId())) {
                stops.add(new GeoPoint(rider.getDropoffLat(), rider.getDropoffLon()));
            }
        }
        return new Plan(party.getVersion(), new GeoPoint(party.getHub().getLat(), party.getHub().getLon()), stops);
    }

    private List<Long> cumulativeFares(Plan plan) {
        List<Long> fares = new ArrayList<>();
        for (int i = 1; i <= plan.stops().size(); i++) {
            long fare = routes.route(plan.hub(), plan.stops().subList(0, i)).fare();
            fares.add(i == 1 ? fare : Math.max(fare, fares.get(i - 2)));
        }
        return fares;
    }

    private void cancel(TransactionTemplate tx, long partyId, Set<Long> leaving, Instant now) {
        tx.executeWithoutResult(status ->
                canceler.cancel(parties.findByIdForUpdate(partyId).orElseThrow(), leaving, now));
    }

    private void apply(long partyId, Set<Long> leaving, Plan plan, List<Long> fares, boolean departed) {
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
        List<Long> units = SettlementCalculator.ratioUnits(fares);
        long total = fares.get(fares.size() - 1);
        List<Long> shares = SettlementCalculator.shares(total, units);
        for (int i = 0; i < stay.size(); i++) {
            RideRequest rider = stay.get(i);
            rider.setDropoffOrder(i + 1);
            rider.setRatioUnits(units.get(i));
            rider.setExpectedShare(shares.get(i));
            if (departed) {
                rider.finish(RequestStatus.COMPLETED);
            }
            mail.send(rider.getMember().getEmail(), "[캠퍼스 택시메이트] 일행 변경",
                    "일행 중 한 명이 빠져 몫과 결제자가 다시 계산됐습니다. 앱에서 확인해 주세요.");
        }
        party.setExpectedTotal(total);
        party.setPayer(stay.get(stay.size() - 1).getMember());
    }
}
