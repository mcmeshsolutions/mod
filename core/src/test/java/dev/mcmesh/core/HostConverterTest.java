package dev.mcmesh.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class HostConverterTest {

    @Test
    public void testValidWorldSaveCheck(@TempDir Path tempDir) throws IOException {
        assertFalse(HostConverter.isValidWorldSave(null));
        assertFalse(HostConverter.isValidWorldSave(tempDir.resolve("nonexistent")));

        Path emptyDir = tempDir.resolve("empty");
        Files.createDirectories(emptyDir);
        assertFalse(HostConverter.isValidWorldSave(emptyDir));

        // Create level.dat
        Files.writeString(emptyDir.resolve("level.dat"), "mock level data");
        assertTrue(HostConverter.isValidWorldSave(emptyDir));

        // Region dir check
        Path worldDir2 = tempDir.resolve("world2");
        Files.createDirectories(worldDir2.resolve("region"));
        assertTrue(HostConverter.isValidWorldSave(worldDir2));
    }

    @Test
    public void testFormatInviteLink() {
        assertEquals("https://gw.mcmesh.dev/join/ABCDEF1234",
                HostConverter.formatInviteLink("https://gw.mcmesh.dev/", "ABCDEF1234"));
        assertEquals("http://localhost:8080/join/SECRET",
                HostConverter.formatInviteLink("http://localhost:8080", "SECRET"));
    }

    @Test
    public void testConversionSummaryMarkdown() {
        var summary = new HostConverter.ConversionSummary("world-abc", "INV123", "https://mcmesh.dev/join/INV123");
        String md = summary.discordMarkdown("Homestead SMP");
        assertTrue(md.contains("**Homestead SMP**"));
        assertTrue(md.contains("https://mcmesh.dev/join/INV123"));
        assertTrue(md.contains("`INV123`"));
        assertEquals("World 'world-abc' ready! Invite: https://mcmesh.dev/join/INV123", summary.formatBanner());
    }
}
