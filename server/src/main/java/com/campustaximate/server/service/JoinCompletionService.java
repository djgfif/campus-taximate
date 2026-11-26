package com.campustaximate.server.service;
import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.*;import com.campustaximate.server.repository.*;
import java.util.*;import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 일행 잠금을 보유한 트랜잭션에서 선결제 승인 또는 후불 합류를 확정한다. */
@Service @RequiredArgsConstructor
public class JoinCompletionService {
    private final PartyJoinRepository joins;private final PartyJoinMemberRepository members;
    private final RideRequestRepository requests;private final PartyChangeService changes;private final MemberSafetyService safety;
    private final NotificationService notifications;private final AppClock clock;
    private final PartyChatService chatLog;
    public boolean pending(PaymentOrder order,Party party){
        return joins.findByPaymentOrderId(order.getId()).filter(join -> canComplete(party, join)).isPresent();
    }

    private boolean canComplete(Party party, PartyJoin join) {
        return "PENDING".equals(join.getState()) && Objects.equals(party.getId(), join.getPartyId())
                && Objects.equals(party.getPayer().getId(), join.getPayerId())
                && party.getGroupVersion() == join.getBaseGroupVersion() && party.canAcceptJoin()
                && (party.postpaidFlow() || Objects.equals(party.getPendingJoinId(), join.getId()))
                && clock.now().isBefore(join.getDeadlineAt());
    }
    /** true면 만료된 합류의 입금이므로 전액 환불한다. 일반 선결제에는 개입하지 않는다. */
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean approved(PaymentOrder order, Party party) {
        var join = joins.findByPaymentOrderId(order.getId()).orElse(null);
        if (join == null || "CONFIRMED".equals(join.getState()) || complete(party, join)) return false;
        reject(join, party);
        return true;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public boolean complete(Party party, PartyJoin join) {
        if (!canComplete(party, join)) return false;
        var newcomer = requests.findById(join.getRequestId()).orElseThrow();
        var current = requests.findByPartyOrderByRideOrder(party);
        var plan = members.findByJoinIdOrderByRideOrder(join.getId());
        boolean waiting = party.postpaidFlow()
                ? newcomer.getStatus() == RequestStatus.WAITING && newcomer.getParty() == null
                : newcomer.getStatus() == RequestStatus.JOINING && Objects.equals(newcomer.getPendingJoinId(), join.getId());
        if (!waiting || current.size() + 1 != plan.size()) return false;
        Map<Long, RideRequest> riders = new HashMap<>();
        current.forEach(rider -> riders.put(rider.getId(), rider));
        riders.put(newcomer.getId(), newcomer);
        if (!riders.keySet().equals(plan.stream().map(PartyJoinMember::getRequestId).collect(java.util.stream.Collectors.toSet()))) return false;
        long newcomerId = newcomer.getMember().getId();
        var avoid = safety.avoidMap(riders.values().stream().map(rider -> rider.getMember().getId()).toList());
        if (current.stream().anyMatch(rider -> avoid.getOrDefault(rider.getMember().getId(), Set.of()).contains(newcomerId))) return false;
        for (var row : plan) {
            RideRequest rider = riders.get(row.getRequestId());
            if (rider != newcomer && row.getExpectedShare() > rider.getExpectedShare()) return false;
        }
        for (var row : plan) {
            RideRequest rider = riders.get(row.getRequestId());
            rider.setParty(party);
            rider.setHub(party.getHub());
            rider.setRideOrder(row.getRideOrder());
            rider.setSharedSeconds(row.getRideSeconds());
            rider.setRatioUnits(row.getRatioUnits());
            rider.setExpectedShare(row.getExpectedShare());
            rider.setStatus(RequestStatus.MATCHED);
        }
        newcomer.setPendingJoinId(null);
        party.setPendingJoinId(null);
        party.setExpectedTotal(join.getTotalFare());
        join.setState("CONFIRMED");
        changes.capture(party,List.of(newcomer),false);
        if (party.postpaidFlow()) {
            changes.syncPostpaidShares(party, new ArrayList<>(riders.values()));
        }
        safety.remember(party,List.of(newcomer));
        changes.changed(party,Set.of(),"JOIN_CONFIRMED",Set.of());
        String message = party.postpaidFlow() ? "%s님이 합류했어요. 기존 예상 분담금은 같거나 줄어요."
                : "%s님이 합류했어요. 기존 분담금은 같거나 줄어요.";
        chatLog.system(party, newcomer.getMember().getId(), message);
        for (var rider : requests.findByPartyOrderByRideOrder(party)) {
            String notice = party.postpaidFlow()
                    ? "일행이 합류했어요. 바뀐 예상 분담금은 " + rider.getExpectedShare() + "원이에요."
                    : "일행이 합류했어요. 바뀐 확정 분담금은 " + rider.getExpectedShare() + "원이에요. 먼저 낸 금액과의 차이는 정산 때 돌려드려요.";
            notifications.toMember(rider.getMember(), "[캠퍼스 택시메이트] 합류 확정", notice);
        }
        return true;
    }
    private void reject(PartyJoin join,Party party){
        if("PENDING".equals(join.getState()))join.setState("CANCELED");if(Objects.equals(party.getPendingJoinId(),join.getId()))party.setPendingJoinId(null);
        requests.findById(join.getRequestId()).filter(r->r.getStatus()==RequestStatus.JOINING&&Objects.equals(r.getPendingJoinId(),join.getId()))
            .ifPresent(r->{r.setPendingJoinId(null);r.finish(RequestStatus.CANCELED);});
    }
}
