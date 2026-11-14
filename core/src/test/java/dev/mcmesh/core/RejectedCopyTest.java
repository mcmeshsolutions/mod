package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RejectedCopyTest {
    @Test
    void unmarkedWorldIsLeftAlone(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("mesh-w1"));
        Files.writeString(root.resolve("level.dat"), "x");
        assertNull(RejectedCopy.setAside(root));
        assertNull(RejectedCopy.setAside(tmp.resolve("missing")));
        assertEquals("x", Files.readString(root.resolve("level.dat")));
    }

    @Test
    void markedWorldMovesAsideWholeAndUnmanaged(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("mesh-w1"));
        Files.createDirectories(root.resolve("region"));
        Files.writeString(root.resolve("region/r.0.0.mca"), "chunks");
        Files.writeString(root.resolve("level.dat"), "level");
        Files.writeString(root.resolve("mcmesh.rev"), "R_0007");
        RejectedCopy.mark(root, "R_0007");
        assertTrue(RejectedCopy.marked(root));

        Path kept = RejectedCopy.setAside(root);

        assertNotNull(kept);
        assertEquals(tmp, kept.getParent());
        assertTrue(kept.getFileName().toString().startsWith("mesh-w1.rejected-"));
        assertEquals("chunks", Files.readString(kept.resolve("region/r.0.0.mca")));
        assertEquals("level", Files.readString(kept.resolve("level.dat")));
        // the copy must not look synced, or a later host run would treat it as revision R_0007
        assertFalse(Files.exists(kept.resolve("mcmesh.rev")));
        assertTrue(Files.isDirectory(root));
        try (var s = Files.list(root)) {
            assertEquals(0, s.count());
        }
        assertNull(RejectedCopy.setAside(root));
    }

    @Test
    void secondRejectionGetsItsOwnFolder(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("w"));
        RejectedCopy.mark(root, "R_1");
        Path a = RejectedCopy.setAside(root);
        RejectedCopy.mark(root, "R_1");
        Path b = RejectedCopy.setAside(root);
        assertNotEquals(a, b);
        assertTrue(Files.isDirectory(a));
        assertTrue(Files.isDirectory(b));
    }

    @Test
    void theReasonIsTheMarkerTextAndNullWhenNotMarked(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("w"));
        assertNull(RejectedCopy.reason(root));
        RejectedCopy.mark(root, "unclean-stop");
        assertEquals("unclean-stop", RejectedCopy.reason(root));
        RejectedCopy.mark(root, "w1:R_0004");
        assertEquals("w1:R_0004", RejectedCopy.reason(root));
    }
}
