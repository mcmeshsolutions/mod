package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcmesh.core.Arrival.Mover;
import org.junit.jupiter.api.Test;

class ArrivalTest {
    private static final String D = "minecraft:overworld";

    @Test
    void aRegionAlreadyUnderTheLoadedAreaIsNeededNow() {
        assertEquals(0, Arrival.regionEta(new Mover(D, 100, 100, 0, 0), 160, 0, 0));
        assertEquals(0, Arrival.eta(new Mover(D, 600, 100, 0, 0), 160, 0, 0, 512, 512), "160 blocks from the edge is inside the loaded area");
    }

    @Test
    void aPlayerHeadingStraightAtARegionArrivesAtTheirFullSpeed() {
        // 1,000 blocks from the near edge, loaded area 160, walking at 5 blocks/s straight at it: 840 / 5
        var m = new Mover(D, -1000, 256, 5, 0);
        assertEquals(168, Arrival.eta(m, 160, 0, 0, 512, 512), 1e-9);
    }

    @Test
    void aFasterPlayerArrivesSoonerAndOneWalkingAwayStillComesBackSlowly() {
        double walking = Arrival.eta(new Mover(D, -1000, 256, 5, 0), 160, 0, 0, 512, 512);
        double elytra = Arrival.eta(new Mover(D, -1000, 256, 30, 0), 160, 0, 0, 512, 512);
        double away = Arrival.eta(new Mover(D, -1000, 256, -15, 0), 160, 0, 0, 512, 512);
        assertTrue(elytra < walking, elytra + " " + walking);
        assertEquals(840 / 5.0, away, 1e-9, "running away at 15 still counts as coming back at a third of that, the player may turn");
    }

    @Test
    void aPlayerWhoStandsStillIsAssumedToStartWalking() {
        assertEquals(420, Arrival.eta(new Mover(D, -1000, 256, 0, 0), 160, 0, 0, 512, 512), 1e-9, "840 blocks at the minimum pace of 2");
    }

    @Test
    void theTrackSmoothsMovementAndForgetsATeleport() {
        var t = new Arrival.Track();
        t.update("p", D, 0, 0, 0);
        for (int i = 1; i <= 12; i++) {
            t.update("p", D, i * 50.0, 0, i * 10_000L); // 5 blocks/s for two minutes
        }
        var m = t.movers().get(0);
        assertEquals(5.0, m.vx(), 0.2);
        t.update("p", D, 100_000, 0, 130_000); // 1,000 blocks/s is a teleport
        assertEquals(0.0, t.movers().get(0).vx(), 1e-9);
        t.update("p", "minecraft:the_nether", 0, 0, 140_000);
        assertEquals(0.0, t.movers().get(0).vx(), 1e-9, "a new dimension starts over");
    }
}
