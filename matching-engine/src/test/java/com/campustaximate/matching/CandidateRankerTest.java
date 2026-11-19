package com.campustaximate.matching;

import static com.campustaximate.matching.Fixtures.LEGS;
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
    void largerGroupComesFirstThenBiggerSaving() {
        var anchor = request(1, at(3, 0));
        var b = request(2, at(5, 0));
        var c = request(3, at(7, 0));
        var d = request(4, at(9, 0));

        List<Candidate> ranked = ranker.rank(anchor, List.of(b, c, d), SLOT.plusSeconds(2100), LEGS);

        assertEquals(4, ranked.get(0).size());
        assertEquals(3, ranked.get(1).size());
        // 같은 인원이면 거리 절약이 큰 조합이 앞(먼 사람끼리 묶을수록 절약이 크다)
        assertEquals(List.of(1L, 3L, 4L), ranked.get(1).sortedIds());
        for (int i = 1; i < ranked.size(); i++) {
            Candidate prev = ranked.get(i - 1);
            Candidate next = ranked.get(i);
            assertTrue(prev.size() > next.size() || prev.savedKm() >= next.savedKm());
        }
    }

    @Test
    void onlyFullGroupsAreOfferedBeforeDeadlineSlot() {
        var anchor = request(1, at(3, 0));
        var b = request(2, at(5, 0));
        var c = request(3, at(7, 0));
        var d = request(4, at(9, 0));

        assertTrue(ranker.rank(anchor, List.of(b, c), SLOT, LEGS).isEmpty());
        assertTrue(ranker.rank(anchor, List.of(b, c, d), SLOT, LEGS).stream().allMatch(x -> x.size() == 4));
    }

    @Test
    void pairsAreNotOfferedBeforeDeadlineSlot() {
        var anchor = request(1, at(3, 0), 30);
        var other = request(2, at(5, 0), 30);

        assertTrue(ranker.rank(anchor, List.of(other), SLOT, LEGS).isEmpty());
    }

    @Test
    void pairDeadlineUsesEarliestEndOfTheTwo() {
        var anchor = request(1, at(3, 0), 30);
        var other = request(2, at(5, 0), -5); // 끝 SLOT+25분 → 마감 SLOT

        assertEquals(1, ranker.rank(anchor, List.of(other), SLOT, LEGS).size());
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

        List<Candidate> ranked = ranker.rank(anchor, others, SLOT, LEGS);

        assertFalse(ranked.isEmpty());
        assertTrue(ranked.stream().noneMatch(c -> c.order().contains(outsideLimit)));
    }
}
