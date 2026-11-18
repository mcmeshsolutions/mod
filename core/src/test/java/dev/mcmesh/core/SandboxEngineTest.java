package dev.mcmesh.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class SandboxEngineTest {

    @Test
    public void testOfflineSandboxModeDefault() {
        SandboxEngine engine = new SandboxEngine();
        assertTrue(engine.isOfflineSandboxActive(), "Offline sandbox should be active by default");
        engine.setOfflineSandboxActive(false);
        assertFalse(engine.isOfflineSandboxActive());
    }

    @Test
    public void testScratchpadSessionAndBomTracking() {
        SandboxEngine engine = new SandboxEngine();
        UUID player = UUID.randomUUID();

        assertFalse(engine.isInScratchpad(player));
        var session = engine.startSession(player, "contraption1", 100.5, 64.0, -200.5, "minecraft:overworld");
        assertNotNull(session);
        assertEquals("contraption1", session.name());
        assertTrue(engine.isInScratchpad(player));
        assertTrue(engine.getTimerText(player).startsWith("In scratchpad (00:"));

        // Record block placements
        engine.recordBlockPlaced(player, "minecraft:iron_block");
        engine.recordBlockPlaced(player, "minecraft:iron_block");
        engine.recordBlockPlaced(player, "create:shaft");
        engine.recordBlockPlaced(player, "minecraft:stone");

        // Record block break (decrement)
        engine.recordBlockBroken(player, "minecraft:stone");

        // Finish session and return to survival
        var draft = engine.returnFromSession(player);
        assertNotNull(draft);
        assertFalse(engine.isInScratchpad(player));
        assertEquals("contraption1", draft.name());
        assertTrue(draft.hologramActive());

        Map<String, Integer> bom = draft.bom();
        assertEquals(2, bom.get("minecraft:iron_block"));
        assertEquals(1, bom.get("create:shaft"));
        assertNull(bom.get("minecraft:stone")); // completely decremented
    }

    @Test
    public void testHologramToggleAndBlueprints() {
        SandboxEngine engine = new SandboxEngine();
        UUID player = UUID.randomUUID();
        engine.startSession(player, "clocktower", 0, 70, 0, "minecraft:overworld");
        engine.recordBlockPlaced(player, "minecraft:gold_block");
        engine.returnFromSession(player);

        assertTrue(engine.getDraft("clocktower").hologramActive());
        boolean next = engine.toggleHologram("clocktower", null);
        assertFalse(next);
        assertFalse(engine.getDraft("clocktower").hologramActive());

        engine.toggleHologram("clocktower", true);
        assertTrue(engine.getDraft("clocktower").hologramActive());

        // Blueprint sharing
        assertFalse(engine.isShared("clocktower"));
        assertTrue(engine.shareBlueprint("clocktower"));
        assertTrue(engine.isShared("clocktower"));
        assertTrue(engine.listSharedBlueprints().contains("clocktower"));
    }

    @Test
    public void testCreateSchematicExport(@TempDir Path tempDir) throws IOException {
        SandboxEngine engine = new SandboxEngine();
        UUID player = UUID.randomUUID();
        engine.startSession(player, "gearbox", 10.0, 75.0, 20.0, "minecraft:overworld");
        engine.recordBlockPlaced(player, "create:cogwheel");
        engine.recordBlockPlaced(player, "create:large_cogwheel");
        engine.returnFromSession(player);

        Path schematic = engine.exportCreateSchematic("gearbox", tempDir);
        assertTrue(Files.exists(schematic));
        String content = Files.readString(schematic);
        assertTrue(content.contains("MCMESH_CREATE_SCHEMATIC_V1"));
        assertTrue(content.contains("Name: gearbox"));
        assertTrue(content.contains("create:cogwheel: x1"));
        assertTrue(content.contains("create:large_cogwheel: x1"));
    }

    @Test
    public void testPersistAndLoadDrafts(@TempDir Path tempDir) throws IOException {
        SandboxEngine engine1 = new SandboxEngine();
        engine1.saveDraft("windmill", "minecraft:overworld", 10.0, 64.0, 10.0,
                Map.of("minecraft:white_wool", 8, "create:windmill_bearing", 1));

        Path storage = tempDir.resolve("mcmesh-drafts.txt");
        engine1.persistDrafts(storage);
        assertTrue(Files.exists(storage));

        SandboxEngine engine2 = new SandboxEngine();
        engine2.loadDrafts(storage);
        var loaded = engine2.getDraft("windmill");
        assertNotNull(loaded);
        assertEquals("windmill", loaded.name());
        assertEquals("minecraft:overworld", loaded.dimension());
        assertEquals(8, loaded.bom().get("minecraft:white_wool"));
        assertEquals(1, loaded.bom().get("create:windmill_bearing"));
    }
}
