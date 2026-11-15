package dev.mcmesh.core;

import java.util.List;

/** An inclusive rectangle of region files (32 x 32 chunks each) in one dimension: what a shard host owns, or what the main host gave away. */
public record ShardRegion(String dim, int x1, int z1, int x2, int z2) {
    public boolean contains(String d, int rx, int rz) {
        return dim.equals(d) && rx >= x1 && rx <= x2 && rz >= z1 && rz <= z2;
    }

    /** Whether the chunk is inside the rectangle. */
    public boolean containsChunk(String d, int cx, int cz) {
        return contains(d, cx >> 5, cz >> 5);
    }

    public static boolean any(List<ShardRegion> regions, String dim, int rx, int rz) {
        for (ShardRegion r : regions) {
            if (r.contains(dim, rx, rz)) {
                return true;
            }
        }
        return false;
    }

    /** Block coordinates of the middle of the first region, for a spawn point that is inside the set. */
    public int centerBlockX() {
        return ((x1 + x2 + 1) * 512) / 2;
    }

    public int centerBlockZ() {
        return ((z1 + z2 + 1) * 512) / 2;
    }
}
