package dev.mcmesh.core;

import java.util.HashMap;
import java.util.Map;

/**
 * When will a player's loaded area reach a place? Used to decide what to fetch or generate ahead of time and by when it has to be
 * there. Pure: positions and times in, seconds out. Positions are in blocks and speeds in blocks per second.
 */
public final class Arrival {
    /** Slowest pace assumed, so a player who stands still is not treated as never arriving: they start walking sooner or later. */
    static final double MIN_SPEED = 2.0;
    /** A jump faster than this between two looks is a teleport or a dimension change, not movement. */
    static final double MAX_SPEED = 80.0;
    static final double REGION_BLOCKS = 512;

    /** A player seen at a place, with the speed they have been moving at. */
    public record Mover(String dim, double x, double z, double vx, double vz) { }

    private Arrival() { }

    /**
     * Seconds until the area the player keeps loaded (a circle of {@code loadBlocks}) touches the rectangle, 0 when it already does.
     * The pace used is the larger of the speed toward the rectangle, a third of the overall speed (the player may turn) and
     * {@link #MIN_SPEED}, so a player heading straight at it counts at full speed and one walking away still counts as slowly coming back.
     */
    public static double eta(Mover m, double loadBlocks, double x0, double z0, double x1, double z1) {
        double px = Math.max(x0, Math.min(m.x(), x1));
        double pz = Math.max(z0, Math.min(m.z(), z1));
        double d = Math.hypot(px - m.x(), pz - m.z());
        if (d <= loadBlocks) {
            return 0;
        }
        double toward = Math.max(0, (m.vx() * (px - m.x()) + m.vz() * (pz - m.z())) / d);
        double speed = Math.hypot(m.vx(), m.vz());
        return (d - loadBlocks) / Math.max(toward, Math.max(speed / 3.0, MIN_SPEED));
    }

    /** The same for the region file rx, rz (32 x 32 chunks). */
    public static double regionEta(Mover m, double loadBlocks, int rx, int rz) {
        return eta(m, loadBlocks, rx * REGION_BLOCKS, rz * REGION_BLOCKS, (rx + 1) * REGION_BLOCKS, (rz + 1) * REGION_BLOCKS);
    }

    /** Smooths each player's movement over about ten seconds from the positions it is shown. */
    public static final class Track {
        private static final double HALF_LIFE_MS = 10_000;

        private static final class State {
            String dim;
            double x;
            double z;
            double vx;
            double vz;
            long at;
        }

        private final Map<String, State> seen = new HashMap<>();

        public synchronized void update(String id, String dim, double x, double z, long nowMs) {
            State s = seen.get(id);
            if (s == null || !s.dim.equals(dim) || nowMs <= s.at) {
                State n = new State();
                n.dim = dim;
                n.x = x;
                n.z = z;
                n.at = nowMs;
                seen.put(id, n);
                return;
            }
            double dt = (nowMs - s.at) / 1000.0;
            double ivx = (x - s.x) / dt;
            double ivz = (z - s.z) / dt;
            if (Math.hypot(ivx, ivz) > MAX_SPEED) {
                s.vx = 0;
                s.vz = 0;
            } else {
                double a = 1 - Math.pow(0.5, (nowMs - s.at) / HALF_LIFE_MS);
                s.vx += a * (ivx - s.vx);
                s.vz += a * (ivz - s.vz);
            }
            s.x = x;
            s.z = z;
            s.at = nowMs;
        }

        public synchronized void forget(String id) {
            seen.remove(id);
        }

        public synchronized java.util.List<Mover> movers() {
            java.util.List<Mover> out = new java.util.ArrayList<>();
            for (State s : seen.values()) {
                out.add(new Mover(s.dim, s.x, s.z, s.vx, s.vz));
            }
            return out;
        }

        /** A player the game does not have (research runs) standing at a place. */
        public synchronized void pin(String id, String dim, double x, double z) {
            State n = new State();
            n.dim = dim;
            n.x = x;
            n.z = z;
            n.at = Long.MAX_VALUE;
            seen.put(id, n);
        }
    }
}
