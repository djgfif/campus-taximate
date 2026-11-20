package com.campustaximate.server.service;

import com.campustaximate.matching.RoadLeg;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;

/** Dense primitive storage for the full C7 grid matrix; C6's reader is unchanged. */
final class C7RoadMatrix extends AbstractMap<ExperimentRunner.Pair, RoadLeg> {
    private final Map<Integer, Integer> index;
    private final double[] km;
    private final int[] seconds;
    private final int width;
    private final int[] ids;

    private C7RoadMatrix(Set<Integer> ids) {
        width = ids.size();
        this.ids = ids.stream().mapToInt(Integer::intValue).toArray();
        int cells = Math.toIntExact((long) width * width);
        index = new HashMap<>();
        for (int id : ids) index.put(id, index.size());
        km = new double[cells];
        seconds = new int[cells];
        Arrays.fill(seconds, -1);
    }

    static C7RoadMatrix read(Path path) throws IOException {
        Set<Integer> ids = new TreeSet<>();
        try (var lines = Files.newBufferedReader(path)) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.startsWith("i\t")) continue;
                int a = line.indexOf('\t'), b = line.indexOf('\t', a + 1);
                ids.add(Integer.parseInt(line.substring(0, a)));
                ids.add(Integer.parseInt(line.substring(a + 1, b)));
            }
        }
        if (ids.isEmpty()) throw new IOException("Empty C7 matrix");
        C7RoadMatrix result = new C7RoadMatrix(ids);
        int count = 0;
        try (var lines = Files.newBufferedReader(path)) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.startsWith("i\t")) continue;
                String[] fields = line.split("\t");
                int from = Integer.parseInt(fields[0]), to = Integer.parseInt(fields[1]);
                int cell = result.index.get(from) * result.width + result.index.get(to);
                double distance = Double.parseDouble(fields[2]);
                int duration = Integer.parseInt(fields[3]);
                if (!Double.isFinite(distance) || distance < 0 || duration < 0 || result.seconds[cell] != -1)
                    throw new IOException("Invalid or duplicate C7 matrix cell " + from + "->" + to);
                result.km[cell] = distance;
                result.seconds[cell] = duration;
                count++;
            }
        }
        if (count != result.seconds.length) throw new IOException("Incomplete C7 matrix");
        System.out.println("C7 dense matrix: " + result.width + " nodes, " + count + " ordered legs");
        return result;
    }

    @Override public RoadLeg get(Object key) {
        if (!(key instanceof ExperimentRunner.Pair pair)) return null;
        Integer from = index.get(pair.from()), to = index.get(pair.to());
        if (from == null || to == null) return null;
        int cell = from * width + to;
        return new RoadLeg(km[cell], seconds[cell]);
    }

    @Override public int size() { return seconds.length; }
    @Override public Set<Entry<ExperimentRunner.Pair, RoadLeg>> entrySet() {
        return new AbstractSet<>() {
            @Override public int size() { return seconds.length; }
            @Override public Iterator<Entry<ExperimentRunner.Pair, RoadLeg>> iterator() {
                return new Iterator<>() {
                    private int cell;
                    @Override public boolean hasNext() { return cell < seconds.length; }
                    @Override public Entry<ExperimentRunner.Pair, RoadLeg> next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        int i=cell++;
                        return new SimpleImmutableEntry<>(new ExperimentRunner.Pair(ids[i/width],ids[i%width]),
                            new RoadLeg(km[i],seconds[i]));
                    }
                };
            }
        };
    }

    static void selfTest() throws IOException {
        Path fixture = Files.createTempFile("c7-matrix-", ".tsv");
        try {
            Files.writeString(fixture, "i\tj\tkm\tseconds\n0\t0\t0\t0\n0\t9\t1.5\t100\n9\t0\t2.5\t200\n9\t9\t0\t0\n");
            var m = read(fixture);
            if (m.size() != 4 || m.entrySet().size() != 4 || m.isEmpty() || m.get(new ExperimentRunner.Pair(0,9)).km() != 1.5
                    || m.get(new ExperimentRunner.Pair(9,0)).seconds() != 200
                    || m.get(new ExperimentRunner.Pair(1,9)) != null)
                throw new AssertionError("C7 dense directed lookup");
            Files.writeString(fixture,"i\tj\tkm\tseconds\n0\t9\t1\t1\n");
            try { read(fixture); throw new AssertionError("Incomplete matrix accepted"); }
            catch (IOException expected) { /* required */ }
        } finally { Files.deleteIfExists(fixture); }
    }
}
