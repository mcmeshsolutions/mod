package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcmesh.core.Arrival.Mover;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArrivalPlanTest {
    private static final String D = WorldgenPlanner.DIM;

    @Test
    void tilesAreAskedForBySoonestArrivalAndCarryTheirSeconds() {
        // standing at the middle of region 0,0; the minimum pace of 2 blocks/s applies to every direction
        var planned = WorldgenPlanner.planByArrival(List.of(new Mover(D, 256, 256, 0, 0)), 160, 3, 1000, 0, 100, (x, z) -> x == 0 && z == 0);
        assertFalse(planned.isEmpty());
        long last = 0;
        for (var p : planned) {
            assertTrue(p.etaS() >= last, "soonest first");
            last = p.etaS();
        }
        // the neighbours are 96 blocks from the loaded area's edge: 48 s at the minimum pace
        assertEquals(48, planned.get(0).etaS());
        assertTrue(planned.stream().noneMatch(p -> p.tile().x() == 0 && p.tile().z() == 0), "a taken region is not asked for again");
    }

    @Test
    void aPlayerHeadingEastGetsTheTilesAheadFirstAndOnlyThoseThatCanBeMadeInTime() {
        var m = new Mover(D, 256, 256, 20, 0); // 20 blocks/s, a fast flight
        var planned = WorldgenPlanner.planByArrival(List.of(m), 160, 6, 600, 40, 100, (x, z) -> x == 0 && z == 0);
        assertTrue(planned.stream().allMatch(p -> p.etaS() >= 40 && p.etaS() <= 600));
        var first = planned.get(0).tile();
        assertEquals(0, first.z(), "straight ahead along the row of the player");
        assertTrue(first.x() > 0, "to the east");
        assertTrue(planned.stream().noneMatch(p -> p.tile().x() == 32 && p.tile().z() == 0), "the next region east arrives in under 40 s, which no worker can beat");
    }

    @Test
    void pendingRegionsAreFetchedInTheOrderTheyWillBeNeededAndFarOnesWait() {
        var m = new Mover("minecraft:overworld", 256, 256, 10, 0);
        var keys = List.of("minecraft:overworld|0|0", "minecraft:overworld|4|0", "minecraft:overworld|1|0", "minecraft:overworld|-4|0", "minecraft:overworld|40|40", "minecraft:the_nether|0|0");
        var order = LazyPlan.byArrival(List.of(m), keys, 160, 300);
        assertEquals("minecraft:overworld|0|0", order.get(0));
        assertEquals("minecraft:overworld|1|0", order.get(1));
        assertTrue(order.contains("minecraft:overworld|4|0"), "ahead, 163 s away");
        assertFalse(order.contains("minecraft:overworld|-4|0"), "the same distance behind the player takes three times as long and is past the horizon");
        assertFalse(order.contains("minecraft:overworld|40|40"), "beyond the horizon");
        assertFalse(order.contains("minecraft:the_nether|0|0"), "another dimension");
    }
}
