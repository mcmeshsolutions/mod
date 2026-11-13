package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashMarkerTest {
    @Test
    void cleanRunsLeaveNothingBehind(@TempDir Path dir) {
        Path m = dir.resolve("m");
        assertEquals(-1, CrashMarker.begin(m, 1000));
        assertEquals(true, Files.exists(m));
        CrashMarker.end(m);
        assertFalse(Files.exists(m));
        assertEquals(-1, CrashMarker.begin(m, 2000));
    }

    @Test
    void aSurvivingMarkerIsAnUncleanPreviousRunAndIsRewritten(@TempDir Path dir) {
        Path m = dir.resolve("m");
        CrashMarker.begin(m, 1000);
        assertEquals(1000, CrashMarker.begin(m, 5000));
        assertEquals(5000, CrashMarker.begin(m, 9000));
    }

    @Test
    void anUnreadableMarkerStillCountsAsUnclean(@TempDir Path dir) throws Exception {
        Path m = dir.resolve("m");
        Files.writeString(m, "garbage");
        assertEquals(0, CrashMarker.begin(m, 1000));
    }

    private static Path report(Path dir, String name, String text, long modified) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, text);
        p.toFile().setLastModified(modified);
        return p;
    }

    @Test
    void newestReportSinceIgnoresOldAndNonTextFiles(@TempDir Path dir) throws Exception {
        report(dir, "crash-old.txt", "old", 1_000);
        Path newer = report(dir, "crash-new.txt", "new", 9_000);
        report(dir, "crash-newest.log", "not a report", 10_000);
        assertEquals(newer, CrashMarker.newestReportSince(dir, 5_000));
        assertNull(CrashMarker.newestReportSince(dir, 20_000));
        assertNull(CrashMarker.newestReportSince(dir.resolve("missing"), 0));
    }

    @Test
    void headCapsLongReports(@TempDir Path dir) throws Exception {
        Path p = report(dir, "c.txt", "x".repeat(100), 1);
        assertEquals(10, CrashMarker.head(p, 10).length());
        assertEquals(100, CrashMarker.head(p, 1000).length());
    }
}
