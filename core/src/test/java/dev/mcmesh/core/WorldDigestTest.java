package dev.mcmesh.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class WorldDigestTest {

    @Test
    public void testPlayerLoginTracking() {
        UUID player = UUID.randomUUID();
        long firstLogin = WorldDigest.recordPlayerLogin(player);
        assertEquals(0, firstLogin, "First login should return 0 previous timestamp");

        long stored = WorldDigest.getLastSeen(player);
        assertTrue(stored > 0, "Last seen should be recorded");

        long secondLogin = WorldDigest.recordPlayerLogin(player);
        assertEquals(stored, secondLogin, "Second login should return previous timestamp");
    }

    @Test
    public void testDigestGenerationAndFormatting() {
        UUID player = UUID.randomUUID();
        long now = System.currentTimeMillis() / 1000L;
        long since = now - 7200L; // 2 hours ago

        WorldDigest.WorldDigestData digest = WorldDigest.generateDigest("testworld", player.toString(), "Steve", since);
        assertNotNull(digest);
        assertEquals("testworld", digest.worldId());
        assertEquals("Steve", digest.playerName());
        assertEquals(player.toString(), digest.playerUuid());
        assertFalse(digest.regionalDeltas().isEmpty(), "Regional deltas should not be empty");
        assertFalse(digest.questProgress().isEmpty(), "Quest progress should not be empty");
        assertFalse(digest.milestones().isEmpty(), "Milestones should not be empty");
        assertFalse(digest.bulletins().isEmpty(), "Bulletins should not be empty");

        List<String> chatLines = WorldDigest.formatChatSummary(digest);
        assertNotNull(chatLines);
        assertFalse(chatLines.isEmpty());
        assertTrue(chatLines.get(0).contains("MCMesh World Digest"));
        assertTrue(chatLines.stream().anyMatch(l -> l.contains("Construction Deltas")));
        assertTrue(chatLines.stream().anyMatch(l -> l.contains("FTB Quests")));
    }
}
