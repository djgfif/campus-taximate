package com.campustaximate.server.service;

import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.Settlement;
import java.time.Duration;
import java.time.Instant;

/** Access and cleanup share an inclusive expiration boundary. */
final class MediaRetention {
    static final Duration KEEP = Duration.ofDays(7);
    static final Duration CHAT_AFTER_DEPARTURE = Duration.ofHours(24);

    private MediaRetention() { }

    static boolean receiptExpired(Settlement settlement, Instant now) {
        return !now.isBefore(settlement.getStoredAt().plus(KEEP));
    }

    static Instant chatEndsAt(Party party) {
        Instant limit = party.getDepartureAt().plus(CHAT_AFTER_DEPARTURE);
        Instant settled = party.getSettledAt();
        return settled != null && settled.isBefore(limit) ? settled : limit;
    }

    static boolean chatExpired(Party party, Instant now) {
        return !now.isBefore(chatEndsAt(party).plus(KEEP));
    }
}
