package dev.mcmesh.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PregenSwarmTest {

    @Test
    void directionalConeGeneration() {
        PregenSwarm swarm = new PregenSwarm();
        // Forward vector along +X axis (dirX=1, dirZ=0), radius 320 blocks = 20 chunks
        swarm.planHalo(0, 0, 1.0, 0.0, 320);

        var p = swarm.getProgress();
        assertTrue(p.running());
        assertTrue(p.queued() > 0);

        // When TPS is low, should not poll
        assertNull(swarm.pollNext(18.0, 19.5));

        // When TPS is healthy, polls chunks
        var c1 = swarm.pollNext(20.0, 19.5);
        assertNotNull(c1);
        assertTrue(c1.cx() >= 0);

        swarm.cancel();
        assertFalse(swarm.getProgress().running());
        assertEquals(0, swarm.getProgress().queued());
    }

    @Test
    void radialHaloGeneration() {
        PregenSwarm swarm = new PregenSwarm();
        // Radial halo (zero vector)
        swarm.planHalo(10, 10, 0.0, 0.0, 64);

        assertTrue(swarm.getProgress().queued() > 0);
        var c = swarm.pollNext(19.9, 19.0);
        assertNotNull(c);
    }
}
