package dev.mcmesh.core;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Decentralized background pre-generation swarm coordinator.
 * Generates an exploration halo ahead of player movement vectors during idle server cycles (TPS >= 19.5),
 * eliminating chunk loading lag during Elytra flight or high-speed travel.
 */
public final class PregenSwarm {
    public record ChunkPos(int cx, int cz) {}
    public record Progress(int queued, int completed, boolean running) {}

    private final Deque<ChunkPos> queue = new ArrayDeque<>();
    private final Set<ChunkPos> planned = new HashSet<>();
    private int completed = 0;
    private boolean active = false;

    public PregenSwarm() {}

    /**
     * Plans an exploration halo centered at (startCx, startCz) extending in direction (dirX, dirZ).
     * @param radiusBlocks radius or forward distance in blocks (e.g. 3000 blocks).
     */
    public synchronized void planHalo(int startCx, int startCz, double dirX, double dirZ, int radiusBlocks) {
        int radiusChunks = Math.min(188, Math.max(1, radiusBlocks / 16));
        queue.clear();
        planned.clear();
        completed = 0;

        // If no direction, plan a radial circle
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        double normX = len > 0.001 ? dirX / len : 0;
        double normZ = len > 0.001 ? dirZ / len : 0;

        for (int r = 1; r <= radiusChunks; r++) {
            if (len > 0.001) {
                // Directional cone (forward 45 degrees)
                int targetCx = startCx + (int) Math.round(normX * r);
                int targetCz = startCz + (int) Math.round(normZ * r);
                for (int w = -r / 4; w <= r / 4; w++) {
                    ChunkPos pos = new ChunkPos(targetCx - (int)(normZ * w), targetCz + (int)(normX * w));
                    if (planned.add(pos)) {
                        queue.add(pos);
                    }
                }
            } else {
                // Radial ring
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx * dx + dz * dz <= r * r) {
                            ChunkPos pos = new ChunkPos(startCx + dx, startCz + dz);
                            if (planned.add(pos)) {
                                queue.add(pos);
                            }
                        }
                    }
                }
            }
        }
        active = !queue.isEmpty();
    }

    /**
     * Polls the next chunk to generate if current server TPS is healthy (>= minTps).
     */
    public synchronized ChunkPos pollNext(double currentTps, double minTps) {
        if (!active || currentTps < minTps) {
            return null;
        }
        ChunkPos next = queue.poll();
        if (next != null) {
            completed++;
        } else {
            active = false;
        }
        return next;
    }

    public synchronized void cancel() {
        queue.clear();
        planned.clear();
        active = false;
    }

    public synchronized Progress getProgress() {
        return new Progress(queue.size(), completed, active);
    }
}
