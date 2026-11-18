package dev.mcmesh.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * In-game 3D highlight lens managing translucent bounding boxes and block highlights
 * over structures and machines added or modified while a player was offline.
 */
public final class SinceLens {

    public enum DeltaType {
        ADDED,
        REMOVED,
        MODIFIED
    }

    public enum Mode {
        OFF,
        LAST_LOGIN,
        PAST_24H,
        CUSTOM
    }

    public record BlockDelta(
            int x,
            int y,
            int z,
            String oldBlock,
            String newBlock,
            String builder,
            long timestampSec,
            DeltaType deltaType
    ) {}

    public record BoundingBox3D(
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            String label,
            String builder,
            int blockCount,
            DeltaType primaryType
    ) {
        public boolean contains(int bx, int by, int bz) {
            return bx >= minX && bx <= maxX && by >= minY && by <= maxY && bz >= minZ && bz <= maxZ;
        }

        public double centerDistance(double px, double py, double pz) {
            double cx = (minX + maxX) / 2.0;
            double cy = (minY + maxY) / 2.0;
            double cz = (minZ + maxZ) / 2.0;
            double dx = px - cx;
            double dy = py - cy;
            double dz = pz - cz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public String getTooltip() {
            return String.format("[%s] %s (%d blocks by %s)", primaryType, label, blockCount, builder);
        }
    }

    private boolean active = false;
    private Mode mode = Mode.OFF;
    private long windowStartSeconds = 0;
    private final List<BlockDelta> recordedDeltas = new ArrayList<>();
    private final List<BoundingBox3D> activeBoxes = new ArrayList<>();

    public SinceLens() {}

    public synchronized void activate(Mode mode, long sinceSeconds) {
        this.active = true;
        this.mode = mode != null ? mode : Mode.CUSTOM;
        this.windowStartSeconds = sinceSeconds;
        recluster();
    }

    public synchronized void deactivate() {
        this.active = false;
        this.mode = Mode.OFF;
        this.activeBoxes.clear();
    }

    public synchronized boolean isActive() {
        return active;
    }

    public synchronized Mode getMode() {
        return mode;
    }

    public synchronized long getWindowStartSeconds() {
        return windowStartSeconds;
    }

    public synchronized List<BoundingBox3D> getActiveBoxes() {
        return Collections.unmodifiableList(new ArrayList<>(activeBoxes));
    }

    public synchronized void addDelta(BlockDelta delta) {
        if (delta != null) {
            recordedDeltas.add(delta);
            if (active) {
                recluster();
            }
        }
    }

    public synchronized void setDeltas(List<BlockDelta> deltas) {
        recordedDeltas.clear();
        if (deltas != null) {
            recordedDeltas.addAll(deltas);
        }
        if (active) {
            recluster();
        }
    }

    public synchronized List<BlockDelta> getDeltas() {
        return Collections.unmodifiableList(new ArrayList<>(recordedDeltas));
    }

    public synchronized void recluster() {
        activeBoxes.clear();
        activeBoxes.addAll(clusterDeltas(recordedDeltas, 16));
    }

    /**
     * Clusters spatial block deltas into cohesive 3D bounding boxes.
     * Deltas within `radius` blocks of each other created by the same builder are grouped together.
     */
    public static List<BoundingBox3D> clusterDeltas(List<BlockDelta> deltas, int radius) {
        if (deltas == null || deltas.isEmpty()) {
            return Collections.emptyList();
        }

        List<List<BlockDelta>> clusters = new ArrayList<>();
        int radSq = radius * radius;

        for (BlockDelta delta : deltas) {
            boolean merged = false;
            for (List<BlockDelta> cluster : clusters) {
                for (BlockDelta existing : cluster) {
                    if (existing.builder().equalsIgnoreCase(delta.builder())) {
                        int dx = existing.x() - delta.x();
                        int dy = existing.y() - delta.y();
                        int dz = existing.z() - delta.z();
                        if (dx * dx + dy * dy + dz * dz <= radSq) {
                            cluster.add(delta);
                            merged = true;
                            break;
                        }
                    }
                }
                if (merged) break;
            }
            if (!merged) {
                List<BlockDelta> newCluster = new ArrayList<>();
                newCluster.add(delta);
                clusters.add(newCluster);
            }
        }

        List<BoundingBox3D> boxes = new ArrayList<>();
        int boxIndex = 1;
        for (List<BlockDelta> cluster : clusters) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            int addedCount = 0;
            int removedCount = 0;
            String builder = cluster.get(0).builder();

            for (BlockDelta d : cluster) {
                minX = Math.min(minX, d.x());
                minY = Math.min(minY, d.y());
                minZ = Math.min(minZ, d.z());
                maxX = Math.max(maxX, d.x());
                maxY = Math.max(maxY, d.y());
                maxZ = Math.max(maxZ, d.z());

                if (d.deltaType() == DeltaType.ADDED) addedCount++;
                else if (d.deltaType() == DeltaType.REMOVED) removedCount++;
                else addedCount++;
            }

            DeltaType primary = addedCount >= removedCount ? DeltaType.ADDED : DeltaType.REMOVED;
            String label = "Structure #" + boxIndex;
            boxes.add(new BoundingBox3D(
                    minX, minY, minZ,
                    maxX, maxY, maxZ,
                    label, builder, cluster.size(), primary
            ));
            boxIndex++;
        }

        boxes.sort(Comparator.comparingInt(BoundingBox3D::blockCount).reversed());
        return boxes;
    }

    /**
     * Generates simulated recent block deltas for in-game demonstration or fallback.
     */
    public static List<BlockDelta> mockRecentDeltas(long sinceSeconds) {
        List<BlockDelta> list = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000L;
        long ts = Math.max(sinceSeconds, now - 3600);

        // Workshop addition
        for (int x = 10; x <= 16; x++) {
            for (int z = 20; z <= 24; z++) {
                list.add(new BlockDelta(x, 64, z, "minecraft:air", "create:brass_casing", "MeshMaster", ts, DeltaType.ADDED));
                list.add(new BlockDelta(x, 65, z, "minecraft:air", "create:shaft", "MeshMaster", ts, DeltaType.ADDED));
            }
        }

        // Quarry excavation
        for (int x = -30; x <= -25; x++) {
            for (int y = 50; y <= 55; y++) {
                list.add(new BlockDelta(x, y, -10, "minecraft:stone", "minecraft:air", "DeepDelver", ts + 500, DeltaType.REMOVED));
            }
        }

        return list;
    }
}
