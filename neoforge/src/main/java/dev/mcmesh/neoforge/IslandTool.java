package dev.mcmesh.neoforge;

import dev.mcmesh.core.IslandPlan;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Replays an IslandProbe recording and says whether dynamic multi-host sharding would have paid off in that session:
 * how much of the time the world split into more than one island, how big they were, how often they changed.
 * With an output path it also writes a self-contained spatial debug page (island map, halos, pending regions).
 * Usage: gradle :neoforge:islands -PtoolArgs="&lt;mcmesh-islands.csv&gt; [out.html]"
 */
public final class IslandTool {
    private record Row(long tick, String dim, String kind, String id, int x, int z) { }

    private IslandTool() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: islands <mcmesh-islands.csv> [out.html]");
            return;
        }
        Path source = Path.of(args[0]);
        List<Row> rows = read(source);
        if (rows.isEmpty()) {
            System.out.println("no samples in " + source);
            return;
        }

        Map<Long, Map<String, List<Row>>> byTick = new TreeMap<>();
        for (Row r : rows) {
            byTick.computeIfAbsent(r.tick(), k -> new LinkedHashMap<>()).computeIfAbsent(r.dim(), k -> new ArrayList<>()).add(r);
        }

        Map<String, IslandPlan.Tracker> trackers = new LinkedHashMap<>();
        Map<String, long[]> stats = new LinkedHashMap<>();   // samples, splitSamples, islandSum, maxIslands, contestedSamples
        Map<String, Load> load = new LinkedHashMap<>();
        StringBuilder samplesJson = new StringBuilder("[");
        long biggest = 0;
        boolean firstSample = true;
        for (var tickEntry : byTick.entrySet()) {
            if (!firstSample) {
                samplesJson.append(',');
            }
            firstSample = false;
            samplesJson.append("{\"t\":").append(tickEntry.getKey()).append(",\"d\":{");
            boolean firstDim = true;
            for (var dimEntry : tickEntry.getValue().entrySet()) {
                String dim = dimEntry.getKey();
                List<IslandPlan.Anchor> anchors = new ArrayList<>();
                List<Row> pending = new ArrayList<>();
                Map<Long, Integer> entities = new java.util.HashMap<>();
                double mspt = -1;
                for (Row r : dimEntry.getValue()) {
                    if (r.kind().equals("pending-region")) {
                        pending.add(r);
                    } else if (r.kind().equals("mspt")) {
                        mspt = Long.parseLong(r.id()) / 1000.0;
                    } else if (r.kind().equals("entity")) {
                        entities.merge(dev.mcmesh.core.WorldgenTiles.pack(r.x(), r.z()), Integer.parseInt(r.id()), Integer::sum);
                    } else {
                        anchors.add(new IslandPlan.Anchor(anchorId(r), r.x(), r.z()));
                    }
                }
                var islands = trackers.computeIfAbsent(dim, k -> new IslandPlan.Tracker()).update(anchors);
                int contested = IslandPlan.contested(islands).size();
                load.computeIfAbsent(dim, k -> new Load()).add(islands, entities, mspt);
                long[] s = stats.computeIfAbsent(dim, k -> new long[5]);
                s[0]++;
                if (islands.size() > 1) {
                    s[1]++;
                }
                s[2] += islands.size();
                s[3] = Math.max(s[3], islands.size());
                if (contested > 0) {
                    s[4]++;
                }
                for (var i : islands) {
                    biggest = Math.max(biggest, i.chunkCount());
                }

                if (!firstDim) {
                    samplesJson.append(',');
                }
                firstDim = false;
                samplesJson.append(quote(dim)).append(":{\"a\":[");
                for (int i = 0; i < dimEntry.getValue().size(); i++) {
                    Row r = dimEntry.getValue().get(i);
                    if (r.kind().equals("pending-region")) {
                        continue;
                    }
                    if (samplesJson.charAt(samplesJson.length() - 1) != '[') {
                        samplesJson.append(',');
                    }
                    int island = IslandPlan.ownerOf(islands, dev.mcmesh.core.WorldgenTiles.pack(r.x(), r.z()));
                    samplesJson.append('[').append(r.x()).append(',').append(r.z()).append(',').append(Math.max(island, 0))
                            .append(',').append(r.kind().equals("forced") ? 1 : 0).append(',').append(quote(r.id())).append(']');
                }
                samplesJson.append("],\"c\":").append(contested).append(",\"p\":[");
                for (int i = 0; i < pending.size(); i++) {
                    samplesJson.append(i > 0 ? "," : "").append('[').append(pending.get(i).x()).append(',').append(pending.get(i).z()).append(']');
                }
                samplesJson.append("]}");
            }
            samplesJson.append("}}");
        }
        samplesJson.append(']');

        System.out.println("read " + rows.size() + " rows, " + byTick.size() + " samples (about " + byTick.size() * 2 + " s of play at one sample per 2 s)");
        for (var e : stats.entrySet()) {
            long[] s = e.getValue();
            IslandPlan.Tracker t = trackers.get(e.getKey());
            System.out.printf("%s: %d samples, more than one island in %d (%.1f%%), mean %.2f, max %d, merges %d, splits %d, samples with a contested chunk %d%n",
                    e.getKey(), s[0], s[1], 100.0 * s[1] / s[0], (double) s[2] / s[0], s[3], t.merges(), t.splits(), s[4]);
        }
        System.out.println("largest island: " + biggest + " chunks");
        for (var e : load.entrySet()) {
            e.getValue().report(e.getKey());
        }
        System.out.println("sharding only pays off where a dimension spends a large share of its samples above one island;");
        System.out.println("any contested chunk at all means the merge distance is wrong, not that the session was unusual.");

        if (args.length > 1) {
            writePage(Path.of(args[1]), source, byTick, stats, trackers, samplesJson.toString());
        }
    }

    private static String anchorId(Row r) {
        return r.kind().equals("forced") ? "forced:" + r.x() + ":" + r.z() : "player:" + r.id();
    }

    private static List<Row> read(Path file) throws Exception {
        List<Row> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split(",", -1);
            if (f.length < 6) {
                continue;
            }
            out.add(new Row(Long.parseLong(f[0].trim()), f[1], f[2], f[3], Integer.parseInt(f[4].trim()), Integer.parseInt(f[5].trim())));
        }
        return out;
    }

    private static void writePage(Path out, Path source, Map<Long, Map<String, List<Row>>> byTick,
                                  Map<String, long[]> stats, Map<String, IslandPlan.Tracker> trackers, String samplesJson) throws Exception {
        String template;
        try (InputStream in = IslandTool.class.getResourceAsStream("/island-view.html")) {
            if (in == null) {
                throw new IllegalStateException("island-view.html missing from the jar");
            }
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var dims = new TreeSet<String>();
        byTick.values().forEach(m -> dims.addAll(m.keySet()));

        StringBuilder statsJson = new StringBuilder("{");
        boolean first = true;
        for (var e : stats.entrySet()) {
            long[] s = e.getValue();
            IslandPlan.Tracker t = trackers.get(e.getKey());
            statsJson.append(first ? "" : ",").append(quote(e.getKey()))
                    .append(":{\"samples\":").append(s[0]).append(",\"split\":").append(s[1])
                    .append(",\"merges\":").append(t.merges()).append(",\"splits\":").append(t.splits()).append('}');
            first = false;
        }
        statsJson.append('}');

        StringBuilder dimsJson = new StringBuilder("[");
        first = true;
        for (String d : dims) {
            dimsJson.append(first ? "" : ",").append(quote(d));
            first = false;
        }
        dimsJson.append(']');

        String data = "{\"view\":" + IslandPlan.VIEW + ",\"halo\":" + IslandPlan.HALO
                + ",\"merge\":" + IslandPlan.MERGE_DISTANCE + ",\"split\":" + IslandPlan.SPLIT_DISTANCE
                + ",\"source\":" + quote(source.getFileName().toString())
                + ",\"note\":" + quote("Islands are recomputed here with the same constants the host uses: a group owns "
                        + "every chunk within " + IslandPlan.VIEW + " of a player or forced chunk, groups merge within "
                        + IslandPlan.MERGE_DISTANCE + " chunks and only split past " + IslandPlan.SPLIT_DISTANCE + ". "
                        + "A contested chunk would mean two hosts could write the same chunk and is a bug, not a warning.")
                + ",\"dims\":" + dimsJson + ",\"stats\":" + statsJson + ",\"samples\":" + samplesJson + "}";

        Files.writeString(out, template.replace("__DATA__", data), StandardCharsets.UTF_8);
        System.out.println("wrote " + out + " (" + Files.size(out) / 1024 + " kB)");
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /** What a host was asked to tick and how long it took, per island, to say whether splitting would have helped. */
    private static final class Load {
        /** A host is over budget above this; the tick budget is 50 ms. */
        static final double OVER_MS = 45;
        /** Splitting only counts if the heaviest island is at most this share of the total. */
        static final double WORTH_IT = 0.8;
        static final double MICROS_PER_ENTITY = Double.parseDouble(System.getProperty("mcmesh.perEntityUs", "26"));
        int samples;
        int over;
        int overAndSplit;
        int overAndSplittable;
        double msptSum;
        double msptMax;
        double predictedSum;
        double hostAfterSplitSum;
        long entitySum;
        long entityMax;
        final List<Double> mspts = new ArrayList<>();

        void add(List<IslandPlan.Island> islands, Map<Long, Integer> entities, double mspt) {
            if (mspt < 0) {
                return;
            }
            samples++;
            int total = 0;
            int[] per = new int[islands.size() + 1];
            for (var e : entities.entrySet()) {
                total += e.getValue();
                int owner = IslandPlan.ownerOf(islands, e.getKey());
                per[owner >= 0 && owner < islands.size() ? owner : islands.size()] += e.getValue();
            }
            int heaviest = 0;
            for (int i = 0; i < islands.size(); i++) {
                heaviest = Math.max(heaviest, per[i]);
            }
            mspt = Math.max(mspt, 0);
            msptSum += mspt;
            msptMax = Math.max(msptMax, mspt);
            mspts.add(mspt);
            entitySum += total;
            entityMax = Math.max(entityMax, total);
            predictedSum += total * MICROS_PER_ENTITY / 1000.0;
            if (mspt >= OVER_MS) {
                over++;
                if (islands.size() > 1) {
                    overAndSplit++;
                    if (heaviest <= WORTH_IT * total) {
                        overAndSplittable++;
                        hostAfterSplitSum += mspt * heaviest / Math.max(1, total);
                    }
                }
            }
        }

        void report(String dim) {
            if (samples == 0) {
                return;
            }
            mspts.sort(null);
            System.out.printf("%s load: tick time mean %.1f ms, p95 %.1f ms, max %.1f ms over %d samples; entities ticked mean %.0f, max %d%n", dim, msptSum / samples,
                    mspts.get((int) Math.floor(0.95 * (samples - 1))), msptMax, samples, (double) entitySum / samples, entityMax);
            System.out.printf("%s model check: %.0f microseconds per entity predicts %.1f ms per tick from the entity counts alone, the host measured %.1f ms%n", dim, MICROS_PER_ENTITY,
                    predictedSum / samples, msptSum / samples);
            System.out.printf("%s over budget (%.0f ms or more): %d of %d samples (%.1f%%); of those, more than one island %d, and splitting would have cut the heaviest host by at least %.0f%%: %d%n", dim, OVER_MS,
                    over, samples, 100.0 * over / samples, overAndSplit, 100 * (1 - WORTH_IT), overAndSplittable);
            if (overAndSplittable > 0) {
                System.out.printf("%s in those, the heaviest island host would have run at about %.1f ms instead%n", dim, hostAfterSplitSum / overAndSplittable);
            }
            System.out.println(dim + (over == 0 ? " the host was never over budget, so sharding had nothing to fix in this session" : overAndSplittable == 0
                    ? " the host was over budget but never in a way that splitting islands would have helped" : " sharding would have helped in " + overAndSplittable + " samples"));
        }
    }
}
