package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AnchorDutyTest {
    @Test
    void withoutWakeOnJoinTheAnchorBehavesAsItAlwaysHas() {
        assertTrue(AnchorDuty.shouldWake(false, -1), "the old behaviour is to host whenever the world is free");
        assertFalse(AnchorDuty.shouldRelease(false, 0, Long.MAX_VALUE, 10), "and never to hand it back on its own");
    }

    @Test
    void wakeOnJoinWaitsForSomebodyWhoActuallyTriedToPlay() {
        assertFalse(AnchorDuty.shouldWake(true, -1), "nobody has asked");
        assertTrue(AnchorDuty.shouldWake(true, 0));
        assertTrue(AnchorDuty.shouldWake(true, AnchorDuty.WANTED_FRESH_S));
        assertFalse(AnchorDuty.shouldWake(true, AnchorDuty.WANTED_FRESH_S + 1), "that player gave up long ago");
    }

    @Test
    void theWorldIsOnlyHandedBackWhenItIsEmptyAndTheGraceHasPassed() {
        assertFalse(AnchorDuty.shouldRelease(true, 1, Long.MAX_VALUE, 10), "never while someone is playing");
        assertFalse(AnchorDuty.shouldRelease(true, 0, 9 * 60_000L, 10));
        assertTrue(AnchorDuty.shouldRelease(true, 0, 10 * 60_000L, 10));
    }

    @Test
    void aGraceOfZeroKeepsTheWorldUp() {
        assertFalse(AnchorDuty.shouldRelease(true, 0, Long.MAX_VALUE, 0));
        assertFalse(AnchorDuty.shouldRelease(true, 0, Long.MAX_VALUE, -1));
    }
}
