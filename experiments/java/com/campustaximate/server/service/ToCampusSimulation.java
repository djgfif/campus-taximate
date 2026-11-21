package com.campustaximate.server.service;

import com.campustaximate.matching.*;
import com.campustaximate.server.route.RouteClient;
import com.campustaximate.server.route.RouteClient.Route;
import com.campustaximate.settlement.SettlementCalculator;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * C15 등교 모의 운영. 지금 서비스의 등교는 "승차 지점에 모여 학교로 가는 도착 기준 요청"(MatchRequest.arriving, 방향 FROM_HUB)이다.
 * 학생은 사는 곳의 격자(500m)를 승차 지점으로 삼고, 학교 도착 시각(arriveBy)까지 가야 한다. 매칭·검증은 운영 코드
 * (MatchingRound, RouteGroupVerifier, MatchingSettings.everyMinute: 모집 마감까지 모아 확정)를 그대로 쓴다.
 * 모집 마감은 서버 RideRequestService.arrivalDeadline과 같은 식: 도착 − 단독 소요 − 준비(lead) − 35분, 분 단위로 내림.
 * 일찍 도착 허용은 서버 기본값 10분. 요청은 모집 마감 requestLead분 전에 들어온다고 둔다.
 *
 * 입력: DAY id 캠퍼스위도 캠퍼스경도 / R id 격자번호 위도 경도 km 초 요금 도착초 성별(M|F) / END
 * 경로: 격자 → 캠퍼스 한 구간(로컬 OSRM, C7 보정)만 있다. 같은 학교로 가므로 일행 경로도 이 한 구간이다.
 * 요금 표시: 혼자 = 요금 × (1 + bias), 일행 = 요금 × (1 + bias + margin) (QuotedRouteClient와 같은 규칙).
 */
public final class ToCampusSimulation {
    static final Instant BASE = Instant.parse("2026-11-02T00:00:00Z");

    record Row(long id, long grid, GeoPoint at, double km, int seconds, long fare, long arriveBy, Gender gender) {}

    static final class Day {
        String id;
        GeoPoint campus;
        final List<Row> rows = new ArrayList<>();
    }

    /** 격자 → 캠퍼스 표. 일행 경로(sharedRouteAt)에만 여유분을 붙인다. */
    static final class Table implements RouteClient {
        final Map<GeoPoint, Row> byPoint = new HashMap<>();
        final GeoPoint campus;
        final double bias, margin;
        Table(Day day, double bias, double margin) {
            this.campus = day.campus; this.bias = bias; this.margin = margin;
            day.rows.forEach(r -> byPoint.putIfAbsent(r.at(), r));
        }
        Route leg(GeoPoint origin, List<GeoPoint> stops, double markup) {
            Row r = byPoint.get(origin);
            if (r == null || stops.isEmpty() || !stops.stream().allMatch(campus::equals)) throw new RouteUnavailableException("no grid leg", null);
            List<Integer> legs = new ArrayList<>(Collections.nCopies(stops.size(), 0));
            legs.set(0, r.seconds());
            return new Route(r.km(), r.seconds(), Math.max(1, Math.round(r.fare() * (1 + markup))), List.of(), legs);
        }
        public Route route(GeoPoint origin, List<GeoPoint> stops) { return leg(origin, stops, bias); }
        @Override public Route sharedRouteAt(GeoPoint origin, List<GeoPoint> stops, Instant departure) { return leg(origin, stops, bias + margin); }
        public Map<String, RoadLeg> legs(GeoPoint origin, Map<String, GeoPoint> destinations) { return Map.of(); }
        public boolean simulated() { return true; }
    }

    static List<Day> read(Path path) throws IOException {
        List<Day> days = new ArrayList<>(); Day current = null;
        for (String line : Files.readAllLines(path)) {
            String[] p = line.split("\t");
            switch (p[0]) {
                case "DAY" -> { current = new Day(); current.id = p[1]; current.campus = new GeoPoint(Double.parseDouble(p[2]), Double.parseDouble(p[3])); }
                case "R" -> current.rows.add(new Row(Long.parseLong(p[1]), Long.parseLong(p[2]), new GeoPoint(Double.parseDouble(p[3]), Double.parseDouble(p[4])),
                        Double.parseDouble(p[5]), Integer.parseInt(p[6]), Long.parseLong(p[7]), Long.parseLong(p[8]), p[9].equals("F") ? Gender.FEMALE : Gender.MALE));
                case "END" -> { days.add(current); current = null; }
                default -> throw new IllegalArgumentException("unexpected line " + p[0]);
            }
        }
        return days;
    }

    static long deadline(Row r, MatchingSettings s) {
        long d = r.arriveBy() - r.seconds() - s.leadMinutes() * 60L - 35 * 60L;
        return d - Math.floorMod(d, 60);
    }

    static String join(Collection<?> x) { return String.join(";", x.stream().map(Object::toString).toList()); }

    static void day(Day d, double bias, double margin, int early, int requestLead, PrintWriter groups, PrintWriter days) {
        var s = MatchingSettings.everyMinute();
        var routes = new Table(d, bias, margin);
        Map<Long, Long> shown = new HashMap<>();
        List<MatchRequest> all = new ArrayList<>();
        for (Row r : d.rows) {
            shown.put(r.id(), routes.route(r.at(), List.of(d.campus)).fare());
            long dl = deadline(r, s);
            all.add(MatchRequest.arriving(r.id(), r.id(), new Hub(r.grid(), r.at()), r.gender(), BASE.plusSeconds(r.arriveBy() - early * 60L),
                    BASE.plusSeconds(r.arriveBy()), BASE.plusSeconds(dl), d.campus, r.km(), r.seconds()).requested(BASE.plusSeconds(dl - requestLead * 60L), false));
        }
        Set<Long> finished = new HashSet<>();
        Map<RouteGroupVerifier.RejectionKey, Instant> rejected = new HashMap<>();
        LegProvider legs = (a, b) -> Optional.of(a.own().equals(b.own()) ? new RoadLeg(0, 0) : new RoadLeg(999, 99999));
        long start = all.stream().mapToLong(r -> r.requestedAt().getEpochSecond() - BASE.getEpochSecond()).min().orElse(0);
        long end = d.rows.stream().mapToLong(Row::arriveBy).max().orElse(0);
        start -= Math.floorMod(start, 60);
        int matched = 0, expired = 0, groupCount = 0;
        TripPlan plan = TripPlan.of(Direction.FROM_HUB, s);
        for (long second = start; second <= end && finished.size() < all.size(); second += 60) {
            Instant slot = BASE.plusSeconds(second);
            var waiting = all.stream().filter(r -> !finished.contains(r.id()) && !r.requestedAt().isAfter(slot)).toList();
            if (waiting.isEmpty()) continue;
            var verifier = new RouteGroupVerifier(routes, rejected, slot, null, shown, Map.of(), slot, s);
            var result = new MatchingRound(s).run(slot, waiting, legs, verifier);
            for (var g : result.confirmed()) {
                var rt = verifier.routesOf(g.order());
                var units = g.order().stream().map(r -> shown.get(r.id())).toList();
                var shares = SettlementCalculator.shares(rt.total(), SettlementCalculator.ratioUnits(units), plan.payerIndex(units.size()));
                List<Double> waits = new ArrayList<>();
                for (var r : g.order()) {
                    if (!finished.add(r.id())) throw new AssertionError("duplicate matched");
                    waits.add((slot.getEpochSecond() - r.requestedAt().getEpochSecond()) / 60.);
                }
                groups.println(String.join("\t", d.id, "" + (++groupCount), join(g.order().stream().map(MatchRequest::id).toList()), "" + second,
                        "" + (rt.departure().getEpochSecond() - BASE.getEpochSecond()), "" + rt.total(), join(units), join(shares), join(waits),
                        "" + g.order().getFirst().anchorId()));
                matched += g.order().size();
            }
            for (var r : result.expired()) { if (!finished.add(r.id())) throw new AssertionError("duplicate expired"); expired++; }
        }
        expired += all.size() - finished.size();  // 끝까지 묶이지 못한 요청
        if (matched + expired != all.size()) throw new AssertionError("request conservation");
        days.println(String.join(",", d.id, "" + all.size(), "" + matched, "" + expired, "" + groupCount));
        groups.flush(); days.flush();
    }

    public static void main(String[] args) throws Exception {
        List<Day> input = read(Path.of(args[0]));
        double bias = Double.parseDouble(args[3]), margin = Double.parseDouble(args[4]);
        int early = args.length > 5 ? Integer.parseInt(args[5]) : 10, requestLead = args.length > 6 ? Integer.parseInt(args[6]) : 60;
        try (var groups = new PrintWriter(Files.newBufferedWriter(Path.of(args[1])));
             var days = new PrintWriter(Files.newBufferedWriter(Path.of(args[2])))) {
            groups.println("day\tgroup\tids\tconfirm\tdeparture\ttotal\tunits\tshares\twaits\thub");
            days.println("day,requests,matched,expired,groups");
            for (Day d : input) day(d, bias, margin, early, requestLead, groups, days);
        }
        System.err.println("ToCampusSimulation " + input.size() + " days");
    }
}
