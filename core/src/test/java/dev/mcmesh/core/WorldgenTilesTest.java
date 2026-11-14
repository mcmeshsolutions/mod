package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorldgenTilesTest {
    private static Map<Long, byte[]> hashes(WorldgenTiles.Tile t, byte seed) {
        Map<Long, byte[]> m = new HashMap<>();
        for (int x = t.generateFromX(); x < t.generateFromX() + t.generateSpan(); x++) {
            for (int z = t.generateFromZ(); z < t.generateFromZ() + t.generateSpan(); z++) {
                m.put(WorldgenTiles.pack(x, z), new byte[] {seed, (byte) x, (byte) z});
            }
        }
        return m;
    }

    @Test
    void overheadMatchesTheMeasuredNumbers() {
        assertEquals(1.56, WorldgenTiles.overhead(32), 0.01);
        assertEquals(2.25, WorldgenTiles.overhead(16), 0.01);
        assertEquals(1.27, WorldgenTiles.overhead(64), 0.01);
    }

    @Test
    void tilesAreGridAlignedAlsoForNegativeChunks() {
        assertEquals(-32, WorldgenTiles.tileOf("minecraft:overworld", -1, -32, 32).x());
        assertEquals(-32, WorldgenTiles.tileOf("minecraft:overworld", -1, -32, 32).z());
        assertEquals(0, WorldgenTiles.tileOf("minecraft:overworld", 31, 0, 32).x());
        assertTrue(WorldgenTiles.tileOf("minecraft:overworld", -1, -1, 32).owns(-1, -1));
    }

    @Test
    void nearestTileComesFirstAndSmallTilesAreRefused() {
        var tiles = WorldgenTiles.around("minecraft:overworld", 100, 100, 64, 32);
        assertEquals(WorldgenTiles.tileOf("minecraft:overworld", 100, 100, 32), tiles.get(0));
        assertTrue(tiles.size() >= 4);
        assertThrows(IllegalArgumentException.class, () -> WorldgenTiles.around("minecraft:overworld", 0, 0, 64, 16));
    }

    @Test
    void theHaloDoesNotChangeTheDigest() {
        var t = WorldgenTiles.tileOf("minecraft:overworld", 0, 0, 32);
        Map<Long, byte[]> a = hashes(t, (byte) 1);
        Map<Long, byte[]> b = hashes(t, (byte) 1);
        b.put(WorldgenTiles.pack(-1, -1), new byte[] {9});
        assertEquals(WorldgenTiles.Verdict.ACCEPT, WorldgenTiles.verify(WorldgenTiles.digest(t, a), WorldgenTiles.digest(t, b)));
    }

    @Test
    void oneDifferentUsableChunkIsAMismatchAndIsNamed() {
        var t = WorldgenTiles.tileOf("minecraft:overworld", 0, 0, 32);
        Map<Long, byte[]> a = hashes(t, (byte) 1);
        Map<Long, byte[]> b = hashes(t, (byte) 1);
        b.put(WorldgenTiles.pack(5, 7), new byte[] {9});
        assertEquals(WorldgenTiles.Verdict.MISMATCH, WorldgenTiles.verify(WorldgenTiles.digest(t, a), WorldgenTiles.digest(t, b)));
        assertEquals(java.util.List.of(WorldgenTiles.pack(5, 7)), WorldgenTiles.differing(t, a, b));
    }

    @Test
    void anIncompleteTileHasNoDigest() {
        var t = WorldgenTiles.tileOf("minecraft:overworld", 0, 0, 32);
        Map<Long, byte[]> a = hashes(t, (byte) 1);
        a.remove(WorldgenTiles.pack(3, 3));
        assertThrows(IllegalArgumentException.class, () -> WorldgenTiles.digest(t, a));
    }
}
