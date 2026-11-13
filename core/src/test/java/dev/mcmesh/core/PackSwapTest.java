package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackSwapTest {
    @TempDir
    Path tmp;

    @Test
    void aSymlinkedDirectoryInsideModsCannotRedirectAWrite() throws Exception {
        Path game = tmp.resolve("game");
        Path outside = tmp.resolve("outside");
        Files.createDirectories(game.resolve("mods"));
        Files.createDirectories(outside);
        Files.createSymbolicLink(game.resolve("mods/evil"), outside);
        assertTrue(PackSwap.escapesBySymlink(game, "mods/evil/x.jar"));
    }

    @Test
    void ordinaryAndNotYetExistingPathsAreFine() throws Exception {
        Path game = tmp.resolve("game");
        Files.createDirectories(game.resolve("config/sub"));
        assertFalse(PackSwap.escapesBySymlink(game, "config/sub/a.toml"));
        assertFalse(PackSwap.escapesBySymlink(game, "config/new/deeper/a.toml"));
        assertFalse(PackSwap.escapesBySymlink(game, "mods/a.jar"));
    }
}
