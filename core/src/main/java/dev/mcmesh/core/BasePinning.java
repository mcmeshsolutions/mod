package dev.mcmesh.core;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Smart base pinning and cold chunk eviction filter.
 * Protects player-designated bases, chests, and spawn regions from automated CAS pruning,
 * and identifies unvisited exploratory chunks older than a retention threshold.
 */
public final class BasePinning {
    public record ChunkCoord(int cx, int cz) {}
    public record PinnedBase(String name, int centerCx, int centerCz, int radiusChunks, long pinnedAt) {}

    private final Set<ChunkCoord> pinnedChunks = ConcurrentHashMap.newKeySet();
    private final Map<String, PinnedBase> pinnedBases = new ConcurrentHashMap<>();

    public BasePinning() {}

    /** Pins an individual chunk coordinates. */
    public void pinChunk(int cx, int cz) {
        pinnedChunks.add(new ChunkCoord(cx, cz));
    }

    /** Unpins an individual chunk coordinates. */
    public boolean unpinChunk(int cx, int cz) {
        return pinnedChunks.remove(new ChunkCoord(cx, cz));
    }

    /** Pins a named base encompassing a square radius of chunks around a center. */
    public PinnedBase pinBase(String name, int centerCx, int centerCz, int radiusChunks) {
        PinnedBase base = new PinnedBase(name, centerCx, centerCz, radiusChunks, System.currentTimeMillis());
        pinnedBases.put(name, base);
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                pinnedChunks.add(new ChunkCoord(centerCx + dx, centerCz + dz));
            }
        }
        return base;
    }

    /** Removes a named base pin. */
    public boolean unpinBase(String name) {
        PinnedBase base = pinnedBases.remove(name);
        if (base == null) {
            return false;
        }
        // If other bases overlap, keep their chunks pinned; otherwise clear
        recomputePinnedChunks();
        return true;
    }

    private void recomputePinnedChunks() {
        pinnedChunks.clear();
        for (PinnedBase b : pinnedBases.values()) {
            for (int dx = -b.radiusChunks(); dx <= b.radiusChunks(); dx++) {
                for (int dz = -b.radiusChunks(); dz <= b.radiusChunks(); dz++) {
                    pinnedChunks.add(new ChunkCoord(b.centerCx() + dx, b.centerCz() + dz));
                }
            }
        }
    }

    /** Checks whether a chunk is pinned and protected against eviction. */
    public boolean isPinned(int cx, int cz) {
        return pinnedChunks.contains(new ChunkCoord(cx, cz));
    }

    /**
     * Determines whether a chunk is cold and eligible for eviction.
     * A chunk is cold if it has not been modified within retentionDays and is NOT pinned.
     */
    public boolean isEligibleForEviction(int cx, int cz, long lastModifiedMillis, int retentionDays) {
        if (isPinned(cx, cz)) {
            return false;
        }
        long threshold = System.currentTimeMillis() - (retentionDays * 86_400_000L);
        return lastModifiedMillis < threshold;
    }

    public Set<ChunkCoord> getPinnedChunks() {
        return Collections.unmodifiableSet(pinnedChunks);
    }

    public Map<String, PinnedBase> getPinnedBases() {
        return Collections.unmodifiableMap(pinnedBases);
    }
}
