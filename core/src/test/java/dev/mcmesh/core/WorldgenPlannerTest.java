package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcmesh.core.WorldgenPlanner.Player;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorldgenPlannerTest {
    private static final String OW = WorldgenPlanner.DIM;

    @Test
    void tilesAreRegionsAndNeverTheOneThePlayerStandsIn() {
        var out = WorldgenPlanner.plan(List.of(new Player(OW, 5, 5, 0, 0)), 2, 0, 100, (x, z) -> false);
        assertEquals(24, out.size(), "a 5 x 5 block of regions minus the player's own");
        assertFalse(out.stream().anyMatch(t -> t.x() == 0 && t.z() == 0));
        assertTrue(out.stream().allMatch(t -> t.x() % 32 == 0 && t.z() % 32 == 0 && t.size() == 32));
    }

    @Test
    void negativeChunksFallInTheRightRegion() {
        var out = WorldgenPlanner.plan(List.of(new Player(OW, -1, -1, 0, 0)), 1, 0, 100, (x, z) -> false);
        assertEquals(8, out.size());
        assertFalse(out.stream().anyMatch(t -> t.x() == -32 && t.z() == -32), "chunk -1,-1 is in region -1,-1");
        assertTrue(out.stream().anyMatch(t -> t.x() == 0 && t.z() == 0));
    }

    @Test
    void whatIsAlreadyOnDiskOrAskedForIsLeftOut() {
        Set<String> taken = Set.of("1,0", "0,1");
        var out = WorldgenPlanner.plan(List.of(new Player(OW, 5, 5, 0, 0)), 1, 0, 100, (x, z) -> taken.contains(x + "," + z));
        assertEquals(6, out.size());
        assertFalse(out.stream().anyMatch(t -> t.x() == 32 && t.z() == 0));
    }

    @Test
    void theRegionsAheadOfAMovingPlayerComeFirst() {
        var out = WorldgenPlanner.plan(List.of(new Player(OW, 5, 5, 1, 0)), 3, 0, 3, (x, z) -> false);
        assertEquals(3, out.size());
        assertEquals(32, out.get(0).x(), "straight ahead (east), the nearest region on the heading");
        assertEquals(0, out.get(0).z());
        var behind = WorldgenPlanner.plan(List.of(new Player(OW, 5, 5, 1, 0)), 3, 0, 100, (x, z) -> false);
        int ahead = behind.indexOf(new WorldgenTiles.Tile(OW, 64, 0, 32));
        int back = behind.indexOf(new WorldgenTiles.Tile(OW, -64, 0, 32));
        assertTrue(ahead < back, "two regions ahead beat two regions behind");
    }

    @Test
    void skipNearLeavesTheClosestRegionsToTheHost() {
        var out = WorldgenPlanner.plan(List.of(new Player(OW, 5, 5, 0, 0)), 2, 1, 100, (x, z) -> false);
        assertEquals(16, out.size(), "only the outer ring of the 5 x 5 block");
        assertTrue(out.stream().allMatch(t -> Math.max(Math.abs(t.x() / 32), Math.abs(t.z() / 32)) == 2));
    }

    @Test
    void otherDimensionsAndEmptyWorldsPlanNothing() {
        assertTrue(WorldgenPlanner.plan(List.of(new Player("minecraft:the_nether", 0, 0, 0, 0)), 3, 0, 10, (x, z) -> false).isEmpty());
        assertTrue(WorldgenPlanner.plan(List.of(), 3, 0, 10, (x, z) -> false).isEmpty());
    }

    @Test
    void twoPlayersShareTheLimitAndNothingIsListedTwice() {
        var out = WorldgenPlanner.plan(List.of(new Player(OW, 5, 5, 0, 0), new Player(OW, 37, 5, 0, 0)), 2, 0, 100, (x, z) -> false);
        assertEquals(out.size(), out.stream().distinct().count());
        assertEquals(10, WorldgenPlanner.plan(List.of(new Player(OW, 5, 5, 0, 0), new Player(OW, 37, 5, 0, 0)), 2, 0, 10, (x, z) -> false).size());
    }
}
