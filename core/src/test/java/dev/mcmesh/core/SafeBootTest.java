package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeBootTest {
    private static Path world(Path dir) throws Exception {
        Path w = dir.resolve("world");
        Files.createDirectories(w.resolve("region"));
        Files.writeString(w.resolve("level.dat"), "level");
        return w;
    }

    @Test
    void aCleanLastRunLeavesTheWorldAlone(@TempDir Path dir) throws Exception {
        Path w = world(dir);
        assertFalse(SafeBoot.prepare(dir.resolve("marker"), w, true));
        assertFalse(RejectedCopy.marked(w));
    }

    @Test
    void anUncleanLastRunMarksTheWorldAndTheSyncThenSetsItAsideIntact(@TempDir Path dir) throws Exception {
        Path w = world(dir);
        Path marker = dir.resolve("marker");
        CrashMarker.begin(marker, 1234);
        assertTrue(SafeBoot.prepare(marker, w, true));
        assertTrue(RejectedCopy.marked(w));
        assertEquals(1234, CrashMarker.peek(marker), "peeking never rewrites the marker");

        Path kept = RejectedCopy.setAside(w);
        assertNotNull(kept);
        assertEquals("level", Files.readString(kept.resolve("level.dat")), "the old copy is kept, not deleted");
        assertTrue(Files.isDirectory(w));
        assertFalse(Files.exists(w.resolve("level.dat")));
    }

    @Test
    void anEmptyGatewayRevisionKeepsTheLocalCopyBecauseItIsTheOnlyOne(@TempDir Path dir) throws Exception {
        Path w = world(dir);
        Path marker = dir.resolve("marker");
        CrashMarker.begin(marker, 1);
        assertFalse(SafeBoot.prepare(marker, w, false));
        assertFalse(RejectedCopy.marked(w));
    }

    @Test
    void noWorldFolderOrAnEmptyOneIsNothingToDistrust(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("marker");
        CrashMarker.begin(marker, 1);
        assertFalse(SafeBoot.prepare(marker, dir.resolve("missing"), true));
        Path empty = Files.createDirectories(dir.resolve("empty"));
        assertFalse(SafeBoot.prepare(marker, empty, true));
    }

    @Test
    void anUnreadableMarkerStillCountsAsUnclean(@TempDir Path dir) throws Exception {
        Path w = world(dir);
        Path marker = dir.resolve("marker");
        Files.writeString(marker, "garbage");
        assertTrue(SafeBoot.prepare(marker, w, true));
    }
}
