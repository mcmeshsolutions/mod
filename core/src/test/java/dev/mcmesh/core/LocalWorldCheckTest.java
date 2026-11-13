package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalWorldCheckTest {
    private static Path file(Path root, String rel, long modified) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
        p.toFile().setLastModified(modified);
        return p;
    }

    @Test
    void regionsOlderThanTheMarkerAreTrusted(@TempDir Path root) throws Exception {
        Path marker = file(root, "mcmesh.rev", 100_000);
        file(root, "region/r.0.0.mca", 90_000);
        file(root, "DIM-1/region/r.0.0.mca", 100_500);
        file(root, "playerdata/x.dat", 500_000);
        assertTrue(LocalWorldCheck.untouchedSince(root, marker), "non-region files and a write inside the slack do not count");
    }

    @Test
    void aRegionWrittenAfterTheMarkerMeansLocalChanges(@TempDir Path root) throws Exception {
        Path marker = file(root, "mcmesh.rev", 100_000);
        file(root, "region/r.0.0.mca", 90_000);
        file(root, "entities/r.1.1.mca", 100_000 + LocalWorldCheck.SLACK_MS + 1);
        assertFalse(LocalWorldCheck.untouchedSince(root, marker));
    }

    @Test
    void aMissingMarkerOrFolderIsNeverTrusted(@TempDir Path root) throws Exception {
        assertFalse(LocalWorldCheck.untouchedSince(root, root.resolve("mcmesh.rev")));
        Path marker = file(root, "mcmesh.rev", 1);
        assertFalse(LocalWorldCheck.untouchedSince(root.resolve("nope"), marker));
    }
}
