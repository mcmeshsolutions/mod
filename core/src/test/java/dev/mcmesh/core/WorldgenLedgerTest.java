package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WorldgenLedgerTest {
    private static void feed(WorldgenLedger l, WorldgenTiles.Verdict v, int times) {
        for (int i = 0; i < times; i++) {
            l.record(v);
        }
    }

    @Test
    void aFewMismatchesDoNotSwitchDistributionOff() {
        WorldgenLedger l = new WorldgenLedger();
        feed(l, WorldgenTiles.Verdict.MISMATCH, 2);
        feed(l, WorldgenTiles.Verdict.ACCEPT, 20);
        assertTrue(l.distributing());
    }

    @Test
    void anEarlyRunOfMismatchesIsNotJudgedBeforeTheSampleIsBigEnough() {
        WorldgenLedger l = new WorldgenLedger();
        feed(l, WorldgenTiles.Verdict.MISMATCH, WorldgenLedger.MIN_SAMPLE - 1);
        assertTrue(l.distributing());
        l.record(WorldgenTiles.Verdict.MISMATCH);
        assertFalse(l.distributing());
    }

    @Test
    void aPackThatNeverReproducesTurnsItselfOffAndStaysOff() {
        WorldgenLedger l = new WorldgenLedger();
        feed(l, WorldgenTiles.Verdict.MISMATCH, 10);
        assertFalse(l.distributing());
        assertEquals(1.0, l.mismatchRate(), 0.001);
        feed(l, WorldgenTiles.Verdict.ACCEPT, 100);
        assertFalse(l.distributing(), "a pack that failed stays off until the pack changes");
        l.reset();
        assertTrue(l.distributing());
    }
}
