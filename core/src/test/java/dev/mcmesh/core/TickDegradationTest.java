package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TickDegradationTest {
    @Test
    void firesOnlyAfterTenSecondsAboveTheThreshold() {
        TickDegradation d = new TickDegradation();
        assertFalse(d.update(60, 0));
        assertFalse(d.update(60, 9_999));
        assertTrue(d.update(60, 10_000));
    }

    @Test
    void aSingleGoodSampleRestartsTheClock() {
        TickDegradation d = new TickDegradation();
        d.update(60, 0);
        assertFalse(d.update(40, 8_000));
        assertFalse(d.update(60, 9_000));
        assertFalse(d.update(60, 18_999));
        assertTrue(d.update(60, 19_000));
    }

    @Test
    void exactlyTheThresholdIsNotDegraded() {
        TickDegradation d = new TickDegradation();
        d.update(TickDegradation.THRESHOLD_MS, 0);
        assertFalse(d.update(TickDegradation.THRESHOLD_MS, 60_000));
    }

    @Test
    void staysQuietForTenMinutesAfterFiringThenCanFireAgain() {
        TickDegradation d = new TickDegradation();
        d.update(80, 0);
        assertTrue(d.update(80, 10_000));
        for (long t = 11_000; t < 10_000 + 600_000; t += 5_000) {
            assertFalse(d.update(80, t), "quiet at " + t);
        }
        assertTrue(d.update(80, 10_000 + 600_000 + 10_000));
    }

    @Test
    void resetForgetsTheCooldown() {
        TickDegradation d = new TickDegradation();
        d.update(80, 0);
        assertTrue(d.update(80, 10_000));
        d.reset();
        d.update(80, 20_000);
        assertTrue(d.update(80, 30_000));
    }
}
