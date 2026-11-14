package dev.mcmesh.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides which regions of a world a new host needs before it opens the world and which can wait. A region is 32 by 32 chunks;
 * its region, entities and poi chunks always travel together under one key {@code dim|rx|rz}.
 */
public final class LazyPlan {
    /** An anchor is a place the world will need at once: spawn, or where a player last was. Chunk coordinates. */
    public record Anchor(String dim, int cx, int cz) { }

    public record Split(List<RevisionFiles.Chunk> eager, Map<String, List<RevisionFiles.Chunk>> pending) {
        public int pendingChunks() {
            return pending.values().stream().mapToInt(List::size).sum();
        }
    }

    private LazyPlan() { }

    public static String regionKey(String dim, int cx, int cz) {
        return dim + "|" + (cx >> 5) + "|" + (cz >> 5);
    }

    /**
     * Regions within {@code radius} regions of an anchor are eager. A dimension with no anchor is eager as a whole when it has at most
     * {@code smallDimChunks} chunk entries (the nether and the end are usually small), otherwise it waits like the rest.
     */
    /**
     * Regions worth having before the player gets there: the one they stand in, then the ones ahead along the dominant axis of
     * their movement (when they are moving), then the eight around them. Direction is the sign of the chunk delta since the last
     * look, 0 when standing still.
     */
    public static List<String> prefetchKeys(String dim, int cx, int cz, int dirX, int dirZ) {
        int rx = cx >> 5;
        int rz = cz >> 5;
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.add(dim + "|" + rx + "|" + rz);
        int ax = Math.abs(dirX) >= Math.abs(dirZ) ? Integer.signum(dirX) : 0;
        int az = ax == 0 ? Integer.signum(dirZ) : 0;
        if (ax != 0 || az != 0) {
            for (int step = 1; step <= 2; step++) {
                out.add(dim + "|" + (rx + ax * step) + "|" + (rz + az * step));
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                out.add(dim + "|" + (rx + dx) + "|" + (rz + dz));
            }
        }
        return List.copyOf(out);
    }

    public static Set<String> eagerRegions(List<RevisionFiles.Chunk> chunks, List<Anchor> anchors, int radius, int smallDimChunks) {
        Set<String> eager = new TreeSet<>();
        for (Anchor a : anchors) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    eager.add(a.dim() + "|" + ((a.cx() >> 5) + dx) + "|" + ((a.cz() >> 5) + dz));
                }
            }
        }
        Map<String, Integer> perDim = new LinkedHashMap<>();
        for (RevisionFiles.Chunk c : chunks) {
            perDim.merge(c.dim(), 1, Integer::sum);
        }
        Set<String> anchoredDims = new TreeSet<>();
        anchors.forEach(a -> anchoredDims.add(a.dim()));
        for (RevisionFiles.Chunk c : chunks) {
            if (!anchoredDims.contains(c.dim()) && perDim.get(c.dim()) <= smallDimChunks) {
                eager.add(regionKey(c.dim(), c.cx(), c.cz()));
            }
        }
        return eager;
    }

    public static Split split(List<RevisionFiles.Chunk> chunks, Set<String> eagerRegions) {
        List<RevisionFiles.Chunk> now = new ArrayList<>();
        Map<String, List<RevisionFiles.Chunk>> later = new LinkedHashMap<>();
        for (RevisionFiles.Chunk c : chunks) {
            String key = regionKey(c.dim(), c.cx(), c.cz());
            if (eagerRegions.contains(key)) {
                now.add(c);
            } else {
                later.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            }
        }
        return new Split(now, later);
    }
}
