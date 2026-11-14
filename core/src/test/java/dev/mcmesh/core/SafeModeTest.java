package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SafeModeTest {
    private static final long T0 = 1_700_000_000_000L;

    @Test
    void aCleanLastRunChangesNothing() {
        var d = SafeMode.decide(SafeMode.State.FRESH, -1, T0);
        assertEquals(SafeMode.Level.NORMAL, d.level());
        assertFalse(d.careful());
        assertTrue(d.lazyBootstrap());
        assertTrue(d.streaming());
    }

    @Test
    void oneCrashIsBadLuckAndHostsNormally() {
        var d = SafeMode.decide(SafeMode.State.FRESH, T0 - 60_000, T0);
        assertEquals(SafeMode.Level.NORMAL, d.level());
        assertEquals(1, d.crashes());
        assertTrue(d.lazyBootstrap(), "a single crash must not change how the world boots");
    }

    @Test
    void twoCrashesInARowTakeTheSimplePath() {
        var first = SafeMode.decide(SafeMode.State.FRESH, T0 - 60_000, T0);
        var state = SafeMode.next(first, T0);
        var second = SafeMode.decide(state, T0, T0 + 60_000);
        assertEquals(SafeMode.Level.CAREFUL, second.level());
        assertEquals(2, second.crashes());
        assertFalse(second.lazyBootstrap());
        assertFalse(second.streaming());
    }

    @Test
    void theThirdCrashAsksTheOwnerToDecide() {
        var state = new SafeMode.State(2, T0);
        var d = SafeMode.decide(state, T0, T0 + 60_000);
        assertEquals(SafeMode.Level.LAST_RESORT, d.level());
        assertEquals(3, d.crashes());
        String hint = SafeMode.recoveryHint("w1", java.util.List.of("rev9", "rev8", "rev7"));
        assertTrue(hint.contains("rev8"), hint);
        assertTrue(hint.contains("Nothing is reverted automatically"), hint);
    }

    @Test
    void crashesFarApartAreNotAStreak() {
        var old = new SafeMode.State(2, T0);
        var d = SafeMode.decide(old, T0 + SafeMode.WINDOW_MS + 1, T0 + SafeMode.WINDOW_MS + 1);
        assertEquals(1, d.crashes(), "an unrelated crash half an hour later starts over");
        assertEquals(SafeMode.Level.NORMAL, d.level());
    }

    @Test
    void aHealthyRunClearsTheStreak() {
        assertEquals(SafeMode.State.FRESH, SafeMode.healthy());
        var d = SafeMode.decide(SafeMode.healthy(), T0, T0 + 1000);
        assertEquals(1, d.crashes());
    }

    @Test
    void withoutAnEarlierRevisionTheHintSaysSo() {
        assertTrue(SafeMode.recoveryHint("w1", java.util.List.of("only")).contains("no earlier revision"));
        assertTrue(SafeMode.recoveryHint("w1", java.util.List.of()).contains("no earlier revision"));
    }

    @Test
    void stateSurvivesARestartAndABrokenFileIsIgnored() throws Exception {
        Path dir = Files.createTempDirectory("safemode");
        Path f = dir.resolve("state.json");
        assertEquals(SafeMode.State.FRESH, SafeMode.read(f));
        SafeMode.write(f, new SafeMode.State(2, T0));
        assertEquals(new SafeMode.State(2, T0), SafeMode.read(f));
        Files.writeString(f, "not json");
        assertEquals(SafeMode.State.FRESH, SafeMode.read(f), "a damaged state file must not stop the world booting");
    }
}
