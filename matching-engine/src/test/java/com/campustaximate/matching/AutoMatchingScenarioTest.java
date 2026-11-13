package com.campustaximate.matching;

import static com.campustaximate.matching.Fixtures.HUB;
import static com.campustaximate.matching.Fixtures.LEGS;
import static com.campustaximate.matching.Fixtures.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 자동 매칭 테스트 시나리오 S-01 (3차 발표 29장).
 * 서버 스케줄러처럼 1분마다 회차를 돌리고, 확정되거나 실패한 요청은 다음 회차에서 뺀다.
 * 시각은 발표 자료와 같게 17:28~18:15로 둔다(엔진은 시간대와 무관하게 5분 정렬만 본다).
 */
class AutoMatchingScenarioTest {

    private static final Instant DAY = Instant.parse("2026-11-20T00:00:00Z");

    private static Instant hm(String text) {
        String[] p = text.split(":");
        return DAY.plusSeconds(Long.parseLong(p[0]) * 3600 + Long.parseLong(p[1]) * 60);
    }

    private static String hm(Instant t) {
        long s = t.getEpochSecond() - DAY.getEpochSecond();
        return String.format("%02d:%02d", s / 3600, s / 60 % 60);
    }

    private static MatchRequest req(long id, Gender g, double east, String asked, String from, String to, boolean instant) {
        return Fixtures.of(id, id + 100, HUB, g, hm(from), hm(to), at(east, 0)).requested(hm(asked), instant);
    }

    @Test
    void s01ReservationInstantExclusionAndFailureOverOneEvening() {
        MatchRequest a = req(1, Gender.FEMALE, 3.0, "17:28", "18:10", "18:40", false);
        MatchRequest b = req(2, Gender.FEMALE, 4.0, "17:29", "18:10", "18:40", false);
        MatchRequest c = req(3, Gender.FEMALE, 5.0, "17:33", "18:10", "18:40", false);
        MatchRequest d = req(4, Gender.FEMALE, 6.0, "17:34", "18:10", "18:40", false);
        MatchRequest e = req(5, Gender.MALE, 4.0, "17:31", "18:10", "18:40", false);
        MatchRequest f = req(6, Gender.FEMALE, 4.5, "17:34", "18:10", "18:40", false)
                .withAvoidMemberIds(Set.of(a.memberId()));
        // 즉시는 출발 가능 시간을 입력하지 않는다(S37). 엔진은 즉시 요청의 범위를 보지 않으므로 요청 시각부터 1시간으로 둔다.
        MatchRequest g = req(7, Gender.FEMALE, 5.5, "18:02", "18:02", "19:02", true);

        List<MatchRequest> pending = new ArrayList<>(List.of(a, b, c, d, e, f, g));
        MatchingRound round = new MatchingRound(MatchingSettings.everyMinute());
        Map<String, ConfirmedGroup> confirmedAt = new LinkedHashMap<>();
        Map<String, List<MatchRequest>> expiredAt = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();

        for (Instant slot = hm("17:30"); !slot.isAfter(hm("18:15")); slot = slot.plusSeconds(60)) {
            RoundResult r = round.run(slot, pending, LEGS, c2 -> Verdict.PASS);
            for (ConfirmedGroup group : r.confirmed()) {
                confirmedAt.put(hm(slot) + " " + group.order().stream().map(MatchRequest::id).toList(), group);
                group.order().forEach(x -> assertTrue(seen.add(x.id()), "두 번 배정됨: " + x.id()));
                pending.removeAll(group.order());
            }
            if (!r.expired().isEmpty()) {
                expiredAt.put(hm(slot), r.expired());
                pending.removeAll(r.expired());
            }
        }
        System.out.println("confirmed=" + confirmedAt.keySet() + " expired=" + expiredAt);

        // 17:30 A·B 둘뿐, 10분 안 지남 → 대기. 17:35 예약 회차에 4명 확정.
        // F는 A를 차단해서 ABCD와 BCDF 중 기다린 시간이 긴 ABCD.
        assertEquals(List.of("17:35 [1, 2, 3, 4]", "18:02 [6, 7]"), new ArrayList<>(confirmedAt.keySet()));
        ConfirmedGroup first = confirmedAt.get("17:35 [1, 2, 3, 4]");
        assertEquals(hm("18:10"), first.departure());
        assertEquals(d, first.payer());
        // 18:02 즉시 G가 들어오자 1분 회차에서 바로 F와 확정. 즉시 예상 T = 18:02 + 5(결제 준비) + 5(배차 여유) = 18:12가
        // 예약자 F의 출발 가능 범위(18:10~18:40) 안이라 함께 묶인다. 즉시 요청자는 10분 뒤 출발한다.
        ConfirmedGroup second = confirmedAt.get("18:02 [6, 7]");
        assertEquals(hm("18:12"), second.departure());
        assertEquals(g, second.payer());
        // 남자 E는 같이 탈 사람이 없어 모집 마감(18:40 − 25분 = 18:15) 회차에 실패
        assertEquals(Map.of("18:15", List.of(e)), expiredAt);
    }
}
