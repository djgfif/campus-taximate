package com.campustaximate.matching;

import static com.campustaximate.matching.Fixtures.SLOT;
import static com.campustaximate.matching.Fixtures.at;
import static com.campustaximate.matching.Fixtures.bearing;
import static com.campustaximate.matching.Fixtures.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandidateRankerTest {

    private final CandidateRanker ranker = new CandidateRanker(MatchingSettings.defaults());

    @Test
    void largerGroupComesFirstThenShorterRoute() {
        var anchor = request(1, at(3, 0));
        var b = request(2, at(5, 0));
        var c = request(3, at(7, 0));
        var d = request(4, at(9, 0));

        List<Candidate> ranked = ranker.rank(anchor, List.of(b, c, d), SLOT);

        assertEquals(4, ranked.get(0).size());
        assertEquals(List.of(1L, 2L, 3L), ranked.get(1).sortedIds());
    }

    @Test
    void pairsAreNotOfferedBeforeDeadlineSlot() {
        var anchor = request(1, at(3, 0), 30);
        var other = request(2, at(5, 0), 30);

        assertTrue(ranker.rank(anchor, List.of(other), SLOT).isEmpty());
    }

    @Test
    void pairDeadlineUsesEarliestEndOfTheTwo() {
        var anchor = request(1, at(3, 0), 30);
        var other = request(2, at(5, 0), 0);

        assertEquals(1, ranker.rank(anchor, List.of(other), SLOT).size());
    }

    @Test
    void onlyElevenClosestAnglesJoinTheAnchor() {
        var anchor = request(1, bearing(90, 5));
        List<MatchRequest> others = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            others.add(request(10 + i, bearing(90 + i + 1, 6)));
        }
        var outsideLimit = request(99, bearing(120, 6));
        others.add(outsideLimit);

        List<Candidate> ranked = ranker.rank(anchor, others, SLOT);

        assertFalse(ranked.isEmpty());
        assertTrue(ranked.stream().noneMatch(c -> c.dropoffOrder().contains(outsideLimit)));
    }
}
