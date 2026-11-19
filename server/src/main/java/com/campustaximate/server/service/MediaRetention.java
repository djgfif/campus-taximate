package com.campustaximate.server.service;

import com.campustaximate.server.domain.Party;
import com.campustaximate.server.domain.Settlement;
import java.time.Duration;
import java.time.Instant;

/** Access and cleanup share an inclusive expiration boundary. */
final class MediaRetention {
    static final Duration KEEP = Duration.ofDays(7);

    private MediaRetention() { }

    static boolean receiptExpired(Settlement settlement, Instant now) {
        return !now.isBefore(settlement.getStoredAt().plus(KEEP));
    }

    static Instant chatEndsAt(Party party) {
        Instant limit = party.getDepartureAt().plus(Duration.ofHours(24));
        Instant settled = party.getSettledAt();
        return settled != null && settled.isBefore(limit) ? settled : limit;
    }

    static boolean chatExpired(Party party, Instant now) {
        return !now.isBefore(chatEndsAt(party).plus(KEEP));
    }
}
