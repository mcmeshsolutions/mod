package dev.mcmesh.core;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Tile geometry and result checking for distributed worldgen. The numbers come from board/RESEARCH.md section 1. */
public final class WorldgenTiles {
    /** Chunks generated around a tile and thrown away; from the fifth ring inward the stored chunks were identical between runs. */
    public static final int HALO = 4;
    public static final int MIN_SIZE = 32;

    /** A square of size x size chunks whose south-west chunk is (x, z), aligned to the grid so two peers always cut the same tiles. */
    public record Tile(String dimension, int x, int z, int size) {
        public int generateFromX() { return x - HALO; }
        public int generateFromZ() { return z - HALO; }
        public int generateSpan() { return size + 2 * HALO; }

        public boolean owns(int chunkX, int chunkZ) {
            return chunkX >= x && chunkX < x + size && chunkZ >= z && chunkZ < z + size;
        }

        public String id() {
            return dimension + "@" + x + "," + z + "/" + size;
        }
    }

    private WorldgenTiles() { }

    /** Work per usable chunk: a 32 tile costs 1.56, a 16 tile 2.25, a 64 tile 1.27. */
    public static double overhead(int size) {
        double span = size + 2.0 * HALO;
        return span * span / ((double) size * size);
    }

    public static Tile tileOf(String dimension, int chunkX, int chunkZ, int size) {
        return new Tile(dimension, Math.floorDiv(chunkX, size) * size, Math.floorDiv(chunkZ, size) * size, size);
    }

    /** Tiles that touch the square of the given radius (in chunks) around a point, nearest tile centre first, so the urgent ring is leased before the far horizon. */
    public static List<Tile> around(String dimension, int centerX, int centerZ, int radius, int size) {
        if (size < MIN_SIZE) {
            throw new IllegalArgumentException("tiles under " + MIN_SIZE + " chunks do more throwaway work than useful work");
        }
        int fromX = Math.floorDiv(centerX - radius, size);
        int toX = Math.floorDiv(centerX + radius, size);
        int fromZ = Math.floorDiv(centerZ - radius, size);
        int toZ = Math.floorDiv(centerZ + radius, size);
        List<Tile> out = new ArrayList<>();
        for (int tx = fromX; tx <= toX; tx++) {
            for (int tz = fromZ; tz <= toZ; tz++) {
                out.add(new Tile(dimension, tx * size, tz * size, size));
            }
        }
        out.sort(Comparator.comparingDouble((Tile t) -> {
            double dx = t.x() + size / 2.0 - centerX;
            double dz = t.z() + size / 2.0 - centerZ;
            return dx * dx + dz * dz;
        }).thenComparingInt(Tile::x).thenComparingInt(Tile::z));
        return out;
    }

    /** One digest over the per-chunk hashes of the usable chunks only, in position order. Halo chunks are not part of it. */
    public static byte[] digest(Tile tile, Map<Long, byte[]> chunkHashes) {
        TreeMap<Long, byte[]> sorted = new TreeMap<>();
        for (var e : chunkHashes.entrySet()) {
            int cx = (int) (e.getKey() >> 32);
            int cz = (int) (long) e.getKey();
            if (tile.owns(cx, cz)) {
                sorted.put(e.getKey(), e.getValue());
            }
        }
        if (sorted.size() != tile.size() * tile.size()) {
            throw new IllegalArgumentException("tile " + tile.id() + " needs " + tile.size() * tile.size() + " chunks, got " + sorted.size());
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (var e : sorted.entrySet()) {
                md.update(ByteBuffer.allocate(8).putLong(e.getKey()).array());
                md.update(e.getValue());
            }
            return md.digest();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public enum Verdict { ACCEPT, MISMATCH }

    /** A tile is accepted only when two independent peers produced the same digest. */
    public static Verdict verify(byte[] first, byte[] second) {
        return java.security.MessageDigest.isEqual(first, second) ? Verdict.ACCEPT : Verdict.MISMATCH;
    }

    /** Chunk positions (packed) whose hashes differ, for the log line that says which part of a tile is not reproducible. */
    public static List<Long> differing(Tile tile, Map<Long, byte[]> a, Map<Long, byte[]> b) {
        List<Long> out = new ArrayList<>();
        for (int cx = tile.x(); cx < tile.x() + tile.size(); cx++) {
            for (int cz = tile.z(); cz < tile.z() + tile.size(); cz++) {
                long k = pack(cx, cz);
                byte[] x = a.get(k);
                byte[] y = b.get(k);
                if (x == null || y == null || !java.util.Arrays.equals(x, y)) {
                    out.add(k);
                }
            }
        }
        return out;
    }
}
