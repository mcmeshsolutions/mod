package dev.mcmesh.neoforge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Research: moves chunks between two world folders and measures the seam. `install` copies a chunk rectangle (terrain,
 * entities and points of interest) from a worker world into a host world; `compare` then says, ring by ring around that
 * rectangle, whether a world built from installed chunks plus host generation equals one the host generated alone.
 * Run it with the server stopped.
 */
public final class TileTool {
    private static final String[] KINDS = {"region", "entities", "poi"};
    private static final Set<String> IGNORED = Set.of("LastUpdate", "InhabitedTime");

    private TileTool() { }

    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "install" -> install(Path.of(a[1]), Path.of(a[2]), rect(a, 3), a.length > 7 && a[7].equals("strict"));
            case "compare" -> compare(Path.of(a[1]), Path.of(a[2]), rect(a, 3), Integer.parseInt(a[7]));
            case "digest" -> digest(Path.of(a[1]), Path.of(a[2]), rect(a, 3));
            case "selftest" -> System.exit(TileSelfTest.run());
            case "entity-diff" -> entityDiff(Path.of(a[1]), Path.of(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]));
            default -> throw new IllegalArgumentException("install <src> <dst> x1 z1 x2 z2 | compare <a> <b> x1 z1 x2 z2 rings");
        }
    }

    private static int[] rect(String[] a, int from) {
        return new int[] {Integer.parseInt(a[from]), Integer.parseInt(a[from + 1]), Integer.parseInt(a[from + 2]), Integer.parseInt(a[from + 3])};
    }

    private static void install(Path src, Path dst, int[] r, boolean strict) throws IOException {
        int[] copied = new int[KINDS.length];
        Map<String, Integer> statuses = new TreeMap<>();
        try (RegionIo from = new RegionIo(src.resolve("world")); RegionIo to = new RegionIo(dst.resolve("world"))) {
            for (int cx = r[0]; cx <= r[2]; cx++) {
                for (int cz = r[1]; cz <= r[3]; cz++) {
                    for (int k = 0; k < KINDS.length; k++) {
                        CompoundTag t = from.read(KINDS[k], cx, cz);
                        if (t != null) {
                            if (k == 0) {
                                statuses.merge(t.getString("Status"), 1, Integer::sum);
                            }
                            to.write(KINDS[k], cx, cz, t);
                            copied[k]++;
                        }
                    }
                }
            }
        }
        int span = (r[2] - r[0] + 1) * (r[3] - r[1] + 1);
        System.out.println("installed " + span + " chunk positions: region " + copied[0] + ", entities " + copied[1] + ", poi " + copied[2]);
        System.out.println("statuses of the installed terrain chunks: " + statuses);
        if (strict && copied[0] != span) {
            System.out.println("WARNING: " + (span - copied[0]) + " chunks had no terrain entry in the source, the install is incomplete");
            System.exit(3);
        }
    }

    /** The entities of one chunk in two worlds, one per line, so what differs can be read off. */
    private static void entityDiff(Path a, Path b, int cx, int cz) throws IOException {
        try (RegionIo wa = new RegionIo(a.resolve("world")); RegionIo wb = new RegionIo(b.resolve("world"))) {
            for (var w : List.of(wa, wb)) {
                CompoundTag e = CanonicalChunk.entities(w.read("entities", cx, cz));
                System.out.println(w == wa ? "A:" : "B:");
                ListTag l = e.getList("Entities", 10);
                for (int i = 0; i < l.size(); i++) {
                    System.out.println("  " + l.getCompound(i));
                }
            }
        }
    }

    /** How many chunks of a rectangle have the same canonical digest in two worlds, which is what two workers would report to the gateway. */
    private static void digest(Path a, Path b, int[] r) throws IOException {
        long same = 0;
        long different = 0;
        long missing = 0;
        Map<String, Integer> parts = new TreeMap<>();
        List<byte[]> da = new ArrayList<>();
        List<byte[]> db = new ArrayList<>();
        String example = null;
        try (RegionIo wa = new RegionIo(a.resolve("world")); RegionIo wb = new RegionIo(b.resolve("world"))) {
            for (int cx = r[0]; cx <= r[2]; cx++) {
                for (int cz = r[1]; cz <= r[3]; cz++) {
                    CompoundTag ra = wa.read("region", cx, cz);
                    CompoundTag rb = wb.read("region", cx, cz);
                    if (ra == null || rb == null) {
                        missing++;
                        continue;
                    }
                    CompoundTag ea = wa.read("entities", cx, cz);
                    CompoundTag eb = wb.read("entities", cx, cz);
                    CompoundTag pa = wa.read("poi", cx, cz);
                    CompoundTag pb = wb.read("poi", cx, cz);
                    byte[] x = CanonicalChunk.digest(ra, ea, pa);
                    byte[] y = CanonicalChunk.digest(rb, eb, pb);
                    da.add(x);
                    db.add(y);
                    if (java.util.Arrays.equals(x, y)) {
                        same++;
                        continue;
                    }
                    different++;
                    String why = !CanonicalChunk.region(ra).equals(CanonicalChunk.region(rb)) ? "terrain" : !CanonicalChunk.entities(ea).equals(CanonicalChunk.entities(eb)) ? "entities" : "poi";
                    parts.merge(why, 1, Integer::sum);
                    if (example == null) {
                        example = cx + "," + cz + " (" + why + ")";
                    }
                }
            }
        }
        System.out.println("canonical digests: " + same + " same, " + different + " different, " + missing + " chunks missing on a side; differing part " + parts);
        if (example != null) {
            System.out.println("first differing chunk: " + example);
        }
        System.out.println("tile digest A " + CanonicalChunk.hex(CanonicalChunk.tile(da)).substring(0, 16) + " B " + CanonicalChunk.hex(CanonicalChunk.tile(db)).substring(0, 16));
    }

    /** Compare with the fields that legitimately differ per run removed. */
    private static CompoundTag normalised(String kind, CompoundTag t) {
        CompoundTag c = DeterminismTool.withoutLight(t);
        IGNORED.forEach(c::remove);
        if (kind.equals("entities")) {
            ListTag list = c.getList("Entities", 10);
            List<String> flat = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag e = list.getCompound(i).copy();
                stripUuids(e);
                flat.add(e.toString());
            }
            Collections.sort(flat);
            CompoundTag out = new CompoundTag();
            ListTag sorted = new ListTag();
            flat.forEach(s -> sorted.add(net.minecraft.nbt.StringTag.valueOf(s)));
            out.put("Entities", sorted);
            return out;
        }
        return c;
    }

    /** Entities get a random UUID when they are created, so it carries no information about generation. */
    private static void stripUuids(Tag t) {
        if (t instanceof CompoundTag c) {
            c.remove("UUID");
            for (String k : new ArrayList<>(c.getAllKeys())) {
                stripUuids(c.get(k));
            }
        } else if (t instanceof ListTag l) {
            l.forEach(TileTool::stripUuids);
        }
    }

    private static void compare(Path a, Path b, int[] tile, int rings) throws IOException {
        // index 0 is inside the tile, index n is the n-th ring of chunks around it
        long[][] same = new long[rings + 1][KINDS.length];
        long[][] diff = new long[rings + 1][KINDS.length];
        long[][] missing = new long[rings + 1][KINDS.length];
        long[] chunks = new long[rings + 1];
        long[][] cells = new long[rings + 1][2];
        List<Map<String, Integer>> keys = new ArrayList<>();
        List<Map<String, Integer>> verdicts = new ArrayList<>();
        for (int i = 0; i <= rings; i++) {
            keys.add(new TreeMap<>());
            verdicts.add(new TreeMap<>());
        }
        String[] example = new String[1];
        Map<Integer, Integer> depthOfBlockDiffs = new TreeMap<>();
        try (RegionIo wa = new RegionIo(a.resolve("world")); RegionIo wb = new RegionIo(b.resolve("world"))) {
            for (int cx = tile[0] - rings; cx <= tile[2] + rings; cx++) {
                for (int cz = tile[1] - rings; cz <= tile[3] + rings; cz++) {
                    int ring = Math.max(Math.max(tile[0] - cx, cx - tile[2]), Math.max(tile[1] - cz, cz - tile[3]));
                    ring = Math.max(ring, 0);
                    chunks[ring]++;
                    for (int k = 0; k < KINDS.length; k++) {
                        CompoundTag x = wa.read(KINDS[k], cx, cz);
                        CompoundTag y = wb.read(KINDS[k], cx, cz);
                        if (x == null && y == null) {
                            same[ring][k]++;
                        } else if (x == null || y == null) {
                            missing[ring][k]++;
                            if (example[0] == null) {
                                example[0] = KINDS[k] + " " + cx + "," + cz + " exists only in " + (x == null ? "B" : "A");
                            }
                        } else {
                            CompoundTag nx = normalised(KINDS[k], x);
                            CompoundTag ny = normalised(KINDS[k], y);
                            if (nx.equals(ny)) {
                                same[ring][k]++;
                            } else {
                                diff[ring][k]++;
                                if (k == 0) {
                                    Set<String> all = new java.util.TreeSet<>(nx.getAllKeys());
                                    all.addAll(ny.getAllKeys());
                                    for (String key : all) {
                                        if (!java.util.Objects.equals(nx.get(key), ny.get(key))) {
                                            keys.get(ring).merge(key, 1, Integer::sum);
                                        }
                                    }
                                    DeterminismTool.classify(nx, ny, cx, cz, verdicts.get(ring), cells[ring], new long[2], new String[1], new TreeMap<>());
                                    if (ring == 0 && !java.util.Objects.equals(nx.get("sections"), ny.get("sections"))) {
                                        int depth = Math.min(Math.min(cx - tile[0], tile[2] - cx), Math.min(cz - tile[1], tile[3] - cz));
                                        depthOfBlockDiffs.merge(depth, 1, Integer::sum);
                                    }
                                }
                                if (example[0] == null && ring > 0) {
                                    example[0] = KINDS[k] + " " + cx + "," + cz + " (ring " + ring + ") differs";
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.println("tile " + tile[0] + "," + tile[1] + " to " + tile[2] + "," + tile[3] + ", " + rings + " rings; A=" + a + " B=" + b);
        for (int i = 0; i <= rings; i++) {
            if (chunks[i] == 0) {
                continue;
            }
            StringBuilder s = new StringBuilder(i == 0 ? "inside tile " : "ring " + i + "       ");
            s.append(String.format("%5d chunks", chunks[i]));
            for (int k = 0; k < KINDS.length; k++) {
                s.append(String.format(" | %s %d same, %d differ, %d one-sided", KINDS[k], same[i][k], diff[i][k], missing[i][k]));
            }
            System.out.println(s);
            if (cells[i][1] > 0) {
                System.out.println(String.format("             block cells differing: %d in total, %.1f per chunk of the ring", cells[i][1], (double) cells[i][1] / chunks[i]));
            }
            if (!keys.get(i).isEmpty()) {
                System.out.println("             terrain keys that differ: " + keys.get(i));
                System.out.println("             verdicts: " + verdicts.get(i));
            }
        }
        if (!depthOfBlockDiffs.isEmpty()) {
            System.out.println("inside the tile, chunks whose block data differs, by depth from the tile edge (0 = outermost chunk): " + depthOfBlockDiffs);
        }
        if (example[0] != null) {
            System.out.println("first difference outside the tile: " + example[0]);
        }
    }
}
