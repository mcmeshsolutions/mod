package dev.mcmesh.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class BasePinningTest {

    @Test
    void pinIndividualChunk() {
        BasePinning pinning = new BasePinning();
        assertFalse(pinning.isPinned(10, 20));

        pinning.pinChunk(10, 20);
        assertTrue(pinning.isPinned(10, 20));
        assertFalse(pinning.isPinned(10, 21));

        assertTrue(pinning.unpinChunk(10, 20));
        assertFalse(pinning.isPinned(10, 20));
    }

    @Test
    void pinBaseRadius() {
        BasePinning pinning = new BasePinning();
        pinning.pinBase("main_base", 0, 0, 1);

        assertTrue(pinning.isPinned(0, 0));
        assertTrue(pinning.isPinned(1, 1));
        assertTrue(pinning.isPinned(-1, -1));
        assertFalse(pinning.isPinned(2, 0));

        assertEquals(1, pinning.getPinnedBases().size());
        assertTrue(pinning.unpinBase("main_base"));
        assertFalse(pinning.isPinned(0, 0));
    }

    @Test
    void coldEvictionEligibility() {
        BasePinning pinning = new BasePinning();
        long now = System.currentTimeMillis();
        long fortyDaysAgo = now - (40L * 86_400_000L);
        long tenDaysAgo = now - (10L * 86_400_000L);

        // Not pinned, older than 30 days -> eligible
        assertTrue(pinning.isEligibleForEviction(5, 5, fortyDaysAgo, 30));

        // Not pinned, modified recently -> not eligible
        assertFalse(pinning.isEligibleForEviction(5, 5, tenDaysAgo, 30));

        // Pinned, older than 30 days -> protected, not eligible
        pinning.pinChunk(5, 5);
        assertFalse(pinning.isEligibleForEviction(5, 5, fortyDaysAgo, 30));
    }
}
