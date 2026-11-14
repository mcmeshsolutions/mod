package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HostBidTest {
    @Test
    void unknownOrGoodLinkLeavesTheCpuScoreAlone() {
        assertEquals(500.0, HostBid.score(500, -1));
        assertEquals(500.0, HostBid.score(500, 10));
        assertEquals(500.0, HostBid.score(500, 400));
    }

    @Test
    void aSlowLinkScalesTheBidDown() {
        assertEquals(250.0, HostBid.score(500, 5));
        assertEquals(50.0, HostBid.score(500, 1));
    }

    @Test
    void theFloorKeepsAnUnusableLinkBiddingSomething() {
        assertEquals(50.0, HostBid.score(500, 0.01));
        assertEquals(50.0, HostBid.score(500, 0));
    }

    @Test
    void aStrongMachineOnABadLineLosesToAWeakOneOnAGoodLine() {
        assertTrue(HostBid.score(1000, 1) < HostBid.score(300, 50));
    }

    @Test
    void anUnknownCpuScoreIsNotPenalisedTwice() {
        assertEquals(0.0, HostBid.score(0, 1));
        assertEquals(-1.0, HostBid.score(-1, 1));
    }
}
