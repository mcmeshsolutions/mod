package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SessionLossTest {
    @Test
    void aDropRightAfterPlayingInAMeshWorldIsHeld() {
        assertTrue(SessionLoss.holdDisconnectScreen(100_000, 101_000, false, false));
        assertTrue(SessionLoss.holdDisconnectScreen(100_000, 100_000 + SessionLoss.ALIVE_WINDOW_MS, false, false));
    }

    @Test
    void anOldOrNeverStartedSessionShowsTheNormalScreen() {
        assertFalse(SessionLoss.holdDisconnectScreen(100_000, 100_000 + SessionLoss.ALIVE_WINDOW_MS + 1, false, false), "a second kick after the window shows its reason");
        assertFalse(SessionLoss.holdDisconnectScreen(0, 999_999, false, false), "never in a mesh world");
    }

    @Test
    void aSwitchOrStandbyInProgressAlwaysHoldsIt() {
        assertTrue(SessionLoss.holdDisconnectScreen(0, 5, true, false));
        assertTrue(SessionLoss.holdDisconnectScreen(0, 5, false, true));
    }
}
