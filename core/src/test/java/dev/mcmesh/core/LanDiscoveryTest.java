package dev.mcmesh.core;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LanDiscoveryTest {

    @Test
    void testFormatAndParseAnnouncement() throws Exception {
        String worldId = "w-lan-alpha";
        String worldName = "Survival Homelab";
        String packId = "all-the-mods";
        String host = "PlayerOne";
        int port = 25565;

        String formatted = LanDiscovery.formatAnnouncement(worldId, worldName, packId, host, port);
        assertNotNull(formatted);
        assertTrue(formatted.contains("[AD]25565[/AD]"));
        assertTrue(formatted.contains("[MCMESH]"));

        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        LanDiscovery.DiscoveredWorld parsed = LanDiscovery.parseAnnouncement(formatted, loopback);

        assertNotNull(parsed);
        assertEquals(worldId, parsed.worldId());
        assertEquals("Survival Homelab", parsed.worldName());
        assertEquals(packId, parsed.packId());
        assertEquals(host, parsed.hostPlayer());
        assertEquals(port, parsed.port());
        assertEquals(loopback, parsed.address());
        assertFalse(parsed.isExpired(10000));
    }

    @Test
    void testParseVanillaFallback() throws Exception {
        String vanillaAnnouncement = "[MOTD]Vanilla LAN Server[/MOTD][AD]54321[/AD]";
        InetAddress addr = InetAddress.getByName("192.168.1.50");
        LanDiscovery.DiscoveredWorld parsed = LanDiscovery.parseAnnouncement(vanillaAnnouncement, addr);

        assertNotNull(parsed);
        assertEquals(54321, parsed.port());
        assertEquals("Vanilla LAN Server", parsed.worldName());
        assertEquals("default", parsed.packId());
        assertEquals(addr, parsed.address());
    }

    @Test
    void testExpiryCheck() throws Exception {
        InetAddress addr = InetAddress.getByName("127.0.0.1");
        LanDiscovery.DiscoveredWorld world = new LanDiscovery.DiscoveredWorld(
                "w-old", "Old World", "pack", "Host", addr, 25565, System.currentTimeMillis() - 8000L);

        assertTrue(world.isExpired(5000L));
        assertFalse(world.isExpired(12000L));
    }
}
