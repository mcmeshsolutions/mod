package dev.mcmesh.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Decides which tiles a host should ask its workers for next. Pure: player positions in, tiles out.
 * Tiles are whole regions (32 x 32 chunks), so a tile is named by its region. Nearest first, with a bonus for the
 * direction a player is moving, because a tile that arrives after the player has been there is wasted work.
 */
public final class WorldgenPlanner {
    /** Only the overworld is distributed for now; the other dimensions generate differently and are untested. */
    public static final String DIM = "minecraft:overworld";

    /** A player's position in chunks and their heading (any length, zero when standing still). */
    public record Player(String dim, int chunkX, int chunkZ, double headingX, double headingZ) { }

    private WorldgenPlanner() { }

    /** A tile to ask for and the seconds the host expects to have before a player needs it. */
    public record Planned(WorldgenTiles.Tile tile, long etaS) { }

    /**
     * The tiles worth asking workers for, by when they are needed. A tile is wanted when a player's loaded area will reach it within
     * {@code horizonS} (further than that is work spent too early, the player may turn) and not sooner than {@code minEtaS} (a
     * tile no worker can deliver in time is left to the host, which generates it when it is needed exactly as it always did).
     * Soonest first. The arrival time is the earliest over all players.
     */
    public static List<Planned> planByArrival(List<Arrival.Mover> movers, double loadBlocks, int regionsAhead, double horizonS, double minEtaS, int max,
            BiPredicate<Integer, Integer> taken) {
        Map<Long, Double> best = new HashMap<>();
        for (Arrival.Mover m : movers) {
            if (!DIM.equals(m.dim())) {
                continue;
            }
            int pr = (int) Math.floor(m.x() / Arrival.REGION_BLOCKS);
            int pz = (int) Math.floor(m.z() / Arrival.REGION_BLOCKS);
            for (int rx = pr - regionsAhead; rx <= pr + regionsAhead; rx++) {
                for (int rz = pz - regionsAhead; rz <= pz + regionsAhead; rz++) {
                    double eta = Arrival.regionEta(m, loadBlocks, rx, rz);
                    if (eta < minEtaS || eta > horizonS || taken.test(rx, rz)) {
                        continue;
                    }
                    best.merge(((long) rx << 32) | (rz & 0xFFFFFFFFL), eta, Math::min);
                }
            }
        }
        List<Map.Entry<Long, Double>> sorted = new ArrayList<>(best.entrySet());
        sorted.sort(Comparator.<Map.Entry<Long, Double>>comparingDouble(Map.Entry::getValue).thenComparingLong(Map.Entry::getKey));
        List<Planned> out = new ArrayList<>();
        for (var e : sorted) {
            if (out.size() >= max) {
                break;
            }
            int rx = (int) (e.getKey() >> 32);
            int rz = (int) (long) e.getKey();
            out.add(new Planned(new WorldgenTiles.Tile(DIM, rx * WorldgenTiles.MIN_SIZE, rz * WorldgenTiles.MIN_SIZE, WorldgenTiles.MIN_SIZE), (long) Math.ceil(e.getValue())));
        }
        return out;
    }

    /**
     * @param regionsAhead how far from a player to plan, in regions
     * @param skipNear     regions this close (Chebyshev) to a player are left to the host, which needs them sooner than a worker can deliver
     * @param max          at most this many tiles
     * @param taken        true for a region that already has files on disk or was already requested
     */
    public static List<WorldgenTiles.Tile> plan(List<Player> players, int regionsAhead, int skipNear, int max, BiPredicate<Integer, Integer> taken) {
        Map<Long, double[]> best = new HashMap<>();
        for (Player p : players) {
            if (!DIM.equals(p.dim())) {
                continue;
            }
            int pr = Math.floorDiv(p.chunkX(), WorldgenTiles.MIN_SIZE);
            int pz = Math.floorDiv(p.chunkZ(), WorldgenTiles.MIN_SIZE);
            double hl = Math.hypot(p.headingX(), p.headingZ());
            for (int rx = pr - regionsAhead; rx <= pr + regionsAhead; rx++) {
                for (int rz = pz - regionsAhead; rz <= pz + regionsAhead; rz++) {
                    int cheb = Math.max(Math.abs(rx - pr), Math.abs(rz - pz));
                    if (cheb <= skipNear || taken.test(rx, rz)) {
                        continue;
                    }
                    double dx = rx - pr;
                    double dz = rz - pz;
                    double dist = Math.hypot(dx, dz);
                    double along = hl == 0 || dist == 0 ? 0 : (dx * p.headingX() + dz * p.headingZ()) / (hl * dist);
                    // lower is better: closer wins, and a region straight ahead counts as up to two regions nearer
                    double score = dist - 2.0 * along;
                    best.merge(((long) rx << 32) | (rz & 0xFFFFFFFFL), new double[] {score}, (a, b) -> a[0] <= b[0] ? a : b);
                }
            }
        }
        List<Map.Entry<Long, double[]>> sorted = new ArrayList<>(best.entrySet());
        sorted.sort(Comparator.<Map.Entry<Long, double[]>>comparingDouble(e -> e.getValue()[0]).thenComparingLong(Map.Entry::getKey));
        List<WorldgenTiles.Tile> out = new ArrayList<>();
        for (var e : sorted) {
            if (out.size() >= max) {
                break;
            }
            int rx = (int) (e.getKey() >> 32);
            int rz = (int) (long) e.getKey();
            out.add(new WorldgenTiles.Tile(DIM, rx * WorldgenTiles.MIN_SIZE, rz * WorldgenTiles.MIN_SIZE, WorldgenTiles.MIN_SIZE));
        }
        return out;
    }
}
