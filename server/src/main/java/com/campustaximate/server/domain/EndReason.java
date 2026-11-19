package com.campustaximate.server.domain;

/** 일행이 있었던 요청이 어떻게 끝났는지. 일행 정보를 지운 뒤에도 화면과 이용 내역에 남긴다. */
public enum EndReason {
    NO_SHOW, PARTY_CANCELED, LEFT_ALONE, ROUTE_VERIFICATION_EXPIRED,
    BOARDING_UNCONFIRMED, PREPAY_UNPAID, FREE_CANCEL, CHANGE_EXIT_FREE, LATE_CANCEL;

    public static EndReason forExit(String reason) {
        return switch (reason) {
            case "BOARDING_UNCONFIRMED", "PAYER_NO_SHOW" -> BOARDING_UNCONFIRMED;
            case "PREPAY_UNPAID" -> PREPAY_UNPAID;
            case "FREE_CANCEL" -> FREE_CANCEL;
            case "CHANGE_EXIT_FREE" -> CHANGE_EXIT_FREE;
            case "LATE_CANCEL" -> LATE_CANCEL;
            case "ROUTE_VERIFICATION_EXPIRED" -> ROUTE_VERIFICATION_EXPIRED;
            default -> null;
        };
    }
}
