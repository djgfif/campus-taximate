package com.campustaximate.server;

import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.*;
import com.campustaximate.server.repository.*;
import com.campustaximate.server.service.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 기존 회귀 시나리오에서도 새 선결제를 실제 서비스 경로로 승인한다. */
final class PaymentTestSupport {
    static void clear(JdbcTemplate jdbc) {
        for (String table : new String[]{"party_join_member", "party_join", "boarding_vote", "boarding_explanation", "boarding_case", "compensation_entry", "admin_audit", "ride_confirmation", "party_change", "party_member_term", "payment_operation", "payout_ledger", "fare_objection", "settlement_share", "payment_order"}) {
            jdbc.update("delete from " + table);
        }
    }
    static void approveEligible(PaymentService payments, PaymentOrderRepository orders, PartyRepository parties, AppClock clock) {
        for (PaymentOrder order : orders.findAll()) {
            if (order.getStatus() != PaymentStatus.REQUIRED && order.getStatus() != PaymentStatus.OVERDUE) continue;
            Party party = parties.findById(order.getPartyId()).orElse(null);
            if (party == null) continue;
            boolean prepay = order.getPurpose() == PaymentPurpose.PREPAY && java.util.List.of(PartyStatus.PREPAYING,PartyStatus.READY).contains(party.getStatus())
                    && clock.now().isBefore(order.getDeadlineAt());
            boolean extra = order.getPurpose() == PaymentPurpose.ADDITIONAL && party.getStatus() == PartyStatus.SETTLED;
            if (prepay || extra) payments.confirm(order.getMemberId(), order.getOrderId(), "test_" + order.getOrderId(), order.getAmount());
        }
    }
}
