package com.campustaximate.server.service;

import com.campustaximate.matching.*;
import com.campustaximate.server.route.RouteClient;
import com.campustaximate.settlement.SettlementCalculator;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Offline experiment adapter. All selection, route acceptance and ratio allocation use app sources. */
public final class ExperimentRunner {
    private static final Instant BASE = Instant.parse("2026-11-17T03:00:00Z");
    private static double sharedFareFactor = 1.0;
    private static PrintWriter observations;
    private static PrintWriter matchedGroups;
    record Input(long id, int point, GeoPoint own, double km, int seconds, long fare, double arrival, int deadline) {}
    static final class Case {
        String id, cohort, feeMode, experiment;
        GeoPoint hub;
        double factor, speed, a, b, c, tick, min;
        List<Input> requests = new ArrayList<>();
    }
    record Pair(int from, int to) {}
    static final class Model implements RouteClient {
        final Case config;
        final Map<GeoPoint, Input> points = new HashMap<>();
        final Map<Pair, RoadLeg> matrix;
        int calls;
        Model(Case config, Map<Pair, RoadLeg> matrix) {
            this.config = config;
            this.matrix = matrix;
            config.requests.forEach(row -> points.put(row.own(), row));
        }
        RoadLeg between(GeoPoint from, GeoPoint to) {
            if (from.equals(to)) return new RoadLeg(0, 0);
            Input left = points.get(from), right = points.get(to);
            if (from.equals(config.hub) && right != null) return new RoadLeg(right.km(), right.seconds());
            if (to.equals(config.hub) && left != null) {
                if (!matrix.isEmpty()) return required(new Pair(left.point(), 0));
                return new RoadLeg(left.km(), left.seconds());
            }
            if (!matrix.isEmpty() && left != null && right != null) return required(new Pair(left.point(), right.point()));
            double km = from.distanceKm(to) * config.factor;
            return new RoadLeg(km, (int) Math.round(km / config.speed * 3600));
        }
        RoadLeg required(Pair pair) {
            RoadLeg leg = matrix.get(pair);
            if (leg == null) throw new RouteUnavailableException("missing offline leg", null);
            return leg;
        }
        public Route route(GeoPoint origin, List<GeoPoint> stops) {
            calls++;
            double km = 0; int seconds = 0;
            List<Integer> legs = new ArrayList<>();
            GeoPoint previous = origin;
            for (GeoPoint stop : stops) {
                RoadLeg leg = between(previous, stop);
                km += leg.km(); seconds += leg.seconds(); legs.add(leg.seconds()); previous = stop;
            }
            long fare = Math.max(1, (long)(Math.ceil(Math.max(config.min,
                    config.a + config.b * km + config.c * seconds / 60.) / config.tick) * config.tick));
            if (stops.size() == 1 && origin.equals(config.hub) && points.containsKey(stops.getFirst()))
                fare = points.get(stops.getFirst()).fare();
            // Verifier restores recorded solo quote weights via requestFares; only shared price is stressed.
            return new Route(km, seconds, Math.max(1, Math.round(fare * sharedFareFactor)), List.of(), legs);
        }
        public Map<String, RoadLeg> legs(GeoPoint origin, Map<String, GeoPoint> destinations) {
            Map<String, RoadLeg> result = new HashMap<>();
            destinations.forEach((key, point) -> { try { result.put(key, between(origin, point)); }
                catch (RouteUnavailableException ignored) { /* missing input stays unavailable */ } });
            return result;
        }
        public boolean simulated() { return true; }
    }
    static List<Case> readCases(Path path) throws IOException {
        List<Case> result = new ArrayList<>(); Case current = null;
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank()) continue;
            String[] s = line.split("\t");
            switch (s[0]) {
                case "CASE" -> {
                    if (!s[4].equals("FROM_HUB")) throw new IllegalArgumentException("legacy direction excluded");
                    current = new Case(); current.cohort = s[1]; current.id = s[2]; current.experiment = s[3];
                    current.hub = new GeoPoint(Double.parseDouble(s[6]), Double.parseDouble(s[7]));
                    current.factor = Double.parseDouble(s[8]); current.speed = Double.parseDouble(s[9]); current.feeMode = s[10];
                    current.a = Double.parseDouble(s[11]); current.b = Double.parseDouble(s[12]); current.c = Double.parseDouble(s[13]);
                    current.tick = Double.parseDouble(s[14]); current.min = Double.parseDouble(s[15]);
                    if (!s[17].equals("KRW")) throw new IllegalArgumentException("KRW only");
                }
                case "R" -> {
                    if (current == null) throw new IllegalArgumentException("R outside CASE");
                    current.requests.add(new Input(Long.parseLong(s[1]), Integer.parseInt(s[2]),
                            new GeoPoint(Double.parseDouble(s[3]), Double.parseDouble(s[4])), Double.parseDouble(s[5]),
                            Integer.parseInt(s[6]), Long.parseLong(s[7]), Double.parseDouble(s[8]), Integer.parseInt(s[9])));
                }
                case "END" -> {
                    if (current == null || current.requests.isEmpty()) throw new IllegalArgumentException("empty CASE");
                    if (current.requests.stream().map(Input::id).distinct().count() != current.requests.size()) throw new IllegalArgumentException("duplicate request ID");
                    result.add(current); current = null;
                }
                default -> throw new IllegalArgumentException("unexpected input");
            }
        }
        if (current != null) throw new IllegalArgumentException("missing END");
        return result;
    }
    static Map<Pair, RoadLeg> readMatrix(Path path) throws IOException {
        Map<Pair, RoadLeg> result = new HashMap<>();
        if (path == null) return result;
        for (String line : Files.readAllLines(path)) {
            String[] s = line.split("\t"); if (s[0].equals("i")) continue;
            result.put(new Pair(Integer.parseInt(s[0]), Integer.parseInt(s[1])), new RoadLeg(Double.parseDouble(s[2]), Integer.parseInt(s[3])));
        }
        return result;
    }
    static MatchRequest request(Case config, Input row, MatchingSettings settings) {
        Instant requested = BASE.plusNanos(Math.round(row.arrival() * 1e9));
        return new MatchRequest(row.id(), row.id(), Direction.FROM_HUB, 1, config.hub, Gender.MALE,
                BASE.plusSeconds((long)Math.ceil(row.arrival()) + settings.leadMinutes() * 60L),
                BASE.plusSeconds(row.deadline() + settings.leadMinutes() * 60L), null, null,
                row.own(), row.km(), row.seconds()).requested(requested, false);
    }
    static void run(Case config, Map<Pair, RoadLeg> matrix, PrintWriter out) {
        MatchingSettings settings = MatchingSettings.everyMinute();
        List<Input> ordered = config.requests.stream().sorted(Comparator.comparingDouble(Input::arrival).thenComparingLong(Input::id)).toList();
        List<MatchRequest> waiting = new ArrayList<>(); Set<Long> matchedIds = new HashSet<>(); Set<Long> expiredIds = new HashSet<>();
        Model model = new Model(config, matrix); Map<Long, Long> fares = new HashMap<>(); config.requests.forEach(row -> fares.put(row.id(), row.fare()));
        List<Double> ratioSavings = new ArrayList<>(), equalSavings = new ArrayList<>(), delays = new ArrayList<>();
        long soloTotal = 0, paidTotal = 0; int next = 0, groups = 0, rounds = 0, conservationErrors = 0, equalBelowTen = 0;
        double engineMs = 0; int end = ordered.stream().mapToInt(Input::deadline).max().orElse(0) + 360;
        int[] checks = new int[Verdict.values().length];
        Map<RouteGroupVerifier.RejectionKey, Instant> rejected = new HashMap<>();
        for (int second = 0; second <= end && (next < ordered.size() || !waiting.isEmpty()); second += 60) {
            while (next < ordered.size() && ordered.get(next).arrival() <= second) waiting.add(request(config, ordered.get(next++), settings));
            if (waiting.isEmpty()) continue;
            Instant slot = BASE.plusSeconds(second);
            RouteGroupVerifier verifier = new RouteGroupVerifier(model, rejected, slot, null, fares, Map.of(), slot, settings);
            GroupVerifier measured = new GroupVerifier() {
                public Verdict verify(Candidate candidate) { return check(candidate).verdict(); }
                public Verification check(Candidate candidate) {
                    Verification result = verifier.check(candidate); checks[result.verdict().ordinal()]++; return result;
                }
                public boolean recentlyRejected(Candidate candidate) { return verifier.recentlyRejected(candidate); }
            };
            LegProvider legs = (left, right) -> { try { return Optional.of(model.between(left.own(), right.own())); }
                catch (RouteClient.RouteUnavailableException error) { return Optional.empty(); } };
            long started = System.nanoTime();
            RoundResult result = new MatchingRound(settings).run(slot, List.copyOf(waiting), legs, measured);
            engineMs += (System.nanoTime() - started) / 1e6; rounds++;
            Set<Long> finished = new HashSet<>();
            for (ConfirmedGroup group : result.confirmed()) {
                groups++;
                var routes = verifier.routesOf(group.order());
                if (routes == null) throw new AssertionError("missing production verification");
                List<Long> units = routes.solo().stream().map(RouteClient.Route::fare).toList();
                int payer = group.order().indexOf(group.payer());
                if (matchedGroups != null) matchedGroups.printf(Locale.ROOT, "%s\t%s\t%d\t%d\t%s%n", config.cohort,
                        config.id, payer, routes.total(), String.join(",", units.stream().map(String::valueOf).toList()));
                List<Long> ratio = SettlementCalculator.shares(routes.total(), SettlementCalculator.ratioUnits(units), payer);
                List<Long> equal = SettlementCalculator.shares(routes.total(), Collections.nCopies(units.size(), 1L), payer);
                if (ratio.stream().mapToLong(Long::longValue).sum() != routes.total() || equal.stream().mapToLong(Long::longValue).sum() != routes.total()) conservationErrors++;
                paidTotal += routes.total();
                for (int i = 0; i < units.size(); i++) {
                    long id = group.order().get(i).id();
                    if (!matchedIds.add(id) || expiredIds.contains(id)) throw new AssertionError("request counted twice");
                    finished.add(id); soloTotal += units.get(i);
                    ratioSavings.add(1 - ratio.get(i) / (double)units.get(i));
                    double saving = 1 - equal.get(i) / (double)units.get(i); equalSavings.add(saving);
                    if (saving < .10) equalBelowTen++;
                    double delay = Math.max(0, (group.order().get(i).walkSeconds() + routes.rideSeconds().get(i) - routes.solo().get(i).seconds()) / 60.);
                    delays.add(delay);
                    if (observations != null) observations.printf(Locale.ROOT, "%s,%s,%.12f,%.12f,%.12f%n", config.cohort, config.id,
                            ratioSavings.getLast(), saving, delay);
                }
            }
            for (MatchRequest expired : result.expired()) {
                if (!expiredIds.add(expired.id()) || matchedIds.contains(expired.id())) throw new AssertionError("request counted twice");
                finished.add(expired.id());
            }
            waiting.removeIf(row -> finished.contains(row.id()));
        }
        if (!waiting.isEmpty() || next != ordered.size() || matchedIds.size() + expiredIds.size() != config.requests.size()) throw new AssertionError("request conservation");
        int n = config.requests.size(), matched = matchedIds.size();
        out.printf(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%.9f,%.9f,%.9f,%.9f,%.9f,%d,%d,%d,%.9f,%.9f,%.6f,%d,%d,%d,%d,%d,%d%n",
                config.cohort, config.id, n, matched, expiredIds.size(), groups, matched/(double)n,
                mean(ratioSavings), mean(equalSavings), ratioSavings.stream().mapToDouble(Double::doubleValue).sum()/n,
                equalSavings.stream().mapToDouble(Double::doubleValue).sum()/n, soloTotal, paidTotal, soloTotal-paidTotal,
                mean(delays), delays.stream().mapToDouble(Double::doubleValue).max().orElse(0), engineMs, model.calls, conservationErrors, equalBelowTen,
                checks[Verdict.PASS.ordinal()], checks[Verdict.FAIL.ordinal()], checks[Verdict.UNAVAILABLE.ordinal()]);
        out.flush();
    }
    static double mean(List<Double> values) { return values.stream().mapToDouble(Double::doubleValue).average().orElse(0); }
    record FixedGroup(String cohort, int payer, long expected, List<Long> units) {}
    static final class BootstrapTotals {
        long groups, riders, nonpayers, conservation, harmed, additional, refunded, refundAmount, clipped, invalidGroups;
        double saving;
        void add(FixedGroup group, long actual, List<Long> soloActual, boolean equal) {
            List<Long> weights = equal ? Collections.nCopies(group.units().size(), 1L) : group.units();
            List<Long> predicted = SettlementCalculator.shares(group.expected(), weights, group.payer());
            List<Long> shares = SettlementCalculator.shares(actual, weights, group.payer());
            groups++;
            if (shares.stream().mapToLong(Long::longValue).sum() != actual) conservation++;
            for (int i=0; i<shares.size(); i++) {
                riders++;
                saving += 1 - shares.get(i)/(double)soloActual.get(i);
                if (shares.get(i)>soloActual.get(i)) harmed++;
                if (i==group.payer()) continue;
                nonpayers++;
                // Invoke the production prepayment rule, not a second implementation.
                long prepay = PaymentService.prepayAmount(predicted.get(i));
                if (shares.get(i)>prepay) additional++;
                if (shares.get(i)<prepay) { refunded++; refundAmount += prepay-shares.get(i); }
            }
        }
        void write(PrintWriter out, String scale, String method, int repetitions) {
            out.printf(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%.12f,%.12f,%.12f,%.12f,%.6f,%d,%d%n", scale, method,
                repetitions, groups, riders, nonpayers, conservation, saving/Math.max(1,riders),
                harmed/(double)Math.max(1,riders), additional/(double)Math.max(1,nonpayers),
                refunded/(double)Math.max(1,nonpayers), refundAmount/(double)Math.max(1,refunded), clipped, invalidGroups);
        }
    }
    static long draw(long expected, double scale, double[] errors, Random rng, BootstrapTotals totals) {
        double multiplier = 1+scale*errors[rng.nextInt(errors.length)];
        if (multiplier<=0) { totals.clipped++; return 0; }
        long value = Math.max(1, Math.round(expected*multiplier));
        if (value>10_000_000) throw new IllegalArgumentException("Bootstrap fare exceeds production calculator limit; do not silently cap");
        return value;
    }
    static void bootstrap(String[] args) throws Exception {
        List<FixedGroup> groups = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            String[] fields=line.split("\t");
            List<Long> units=Arrays.stream(fields[4].split(",")).map(Long::valueOf).toList();
            groups.add(new FixedGroup(fields[0], Integer.parseInt(fields[2]), Long.parseLong(fields[3]), units));
        }
        double[] errors=Files.lines(Path.of(args[2])).filter(s->!s.isBlank()).mapToDouble(Double::parseDouble).toArray();
        if (errors.length==0 || Arrays.stream(errors).anyMatch(e->!Double.isFinite(e) || e<=-1)) throw new IllegalArgumentException("Invalid empirical distribution");
        int repetitions=Integer.parseInt(args[4]); long seed=Long.parseLong(args[5]);
        if (repetitions<1000 || groups.isEmpty()) throw new IllegalArgumentException("Need >=1000 replicates and matched groups");
        try (PrintWriter out=new PrintWriter(Files.newBufferedWriter(Path.of(args[3])))) {
            out.println("scale,method,replicates,group_settlements,riders,nonpayers,conservation_errors,mean_actual_saving,harmed_rate,additional_payment_rate,refund_rate,mean_refund_krw,nonpositive_multiplier_draws,invalid_scaled_groups");
            for (double scale : new double[]{0,1,1.5,2}) {
                // Same random draws for each scale and both settlements, for paired comparisons.
                Random rng=new Random(seed); BootstrapTotals ratio=new BootstrapTotals(), equal=new BootstrapTotals();
                for (int rep=0; rep<repetitions; rep++) for (FixedGroup group : groups) {
                    long actual=draw(group.expected(),scale,errors,rng,ratio);
                    List<Long> solos=new ArrayList<>();
                    for (long solo : group.units()) solos.add(draw(solo,scale,errors,rng,ratio));
                    // Additive tail amplification can imply impossible nonpositive
                    // fares. Do not invent a 1-won actual fare. Report and exclude
                    // that group-replicate from this stress scenario's denominators.
                    if (actual<=0 || solos.stream().anyMatch(v->v<=0)) { ratio.invalidGroups++; equal.invalidGroups++; continue; }
                    ratio.add(group,actual,solos,false); equal.add(group,actual,solos,true);
                }
                equal.clipped=ratio.clipped;
                ratio.write(out,String.valueOf(scale),"ratio",repetitions); equal.write(out,String.valueOf(scale),"equal",repetitions);
                if (ratio.conservation!=0 || equal.conservation!=0) throw new AssertionError("Bootstrap amount conservation");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--bootstrap")) { bootstrap(args); return; }
        Map<Pair, RoadLeg> matrix = readMatrix(args.length > 2 && !args[2].equals("-") ? Path.of(args[2]) : null);
        if (args.length > 3) sharedFareFactor = Double.parseDouble(args[3]);
        if (!Double.isFinite(sharedFareFactor) || sharedFareFactor <= 0) throw new IllegalArgumentException("invalid fare factor");
        if (args.length > 5) {
            Properties settings = new Properties();
            for (var field : MatchingSettings.class.getRecordComponents())
                settings.setProperty(field.getName(), String.valueOf(field.getAccessor().invoke(MatchingSettings.everyMinute())));
            try (var writer = Files.newBufferedWriter(Path.of(args[5]))) { settings.store(writer, "Current production MatchingSettings.everyMinute()"); }
        }
        if (args.length > 4) {
            observations = new PrintWriter(Files.newBufferedWriter(Path.of(args[4])));
            observations.println("cohort,case_id,ratio_saving,equal_saving,delay_minutes");
        }
        if (args.length > 6) matchedGroups = new PrintWriter(Files.newBufferedWriter(Path.of(args[6])));
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))) {
            out.println("cohort,case_id,requests,matched,expired,groups,matching_rate,mean_ratio_saving_matched,mean_equal_saving_matched,mean_ratio_saving_all,mean_equal_saving_all,solo_total_krw,shared_total_krw,saved_krw,mean_delay_minutes_matched,max_delay_minutes,engine_and_offline_route_ms,offline_route_calls,conservation_errors,equal_saving_below_10pct_count,verification_passes,verification_fails,verification_unavailable");
            for (Case config : readCases(Path.of(args[0]))) run(config, matrix, out);
        } finally {
            if (observations != null) observations.close();
            if (matchedGroups != null) matchedGroups.close();
        }
    }
}
