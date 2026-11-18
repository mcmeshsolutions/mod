package dev.mcmesh.core;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class P2pDirectTest {

    @Test
    void routeEvaluation() {
        P2pDirect p2p = new P2pDirect();
        var lanAddr = new InetSocketAddress("192.168.1.50", 25565);
        var wanAddr = new InetSocketAddress("198.51.100.4", 25565);

        // Fast LAN probe
        var rLan = p2p.evaluateCandidate("peer-lan", lanAddr, true, 8);
        assertEquals(P2pDirect.RouteMode.LAN_LOCAL, rLan.mode());
        assertTrue(p2p.isDirect("peer-lan"));

        // Direct P2P probe with moderate latency
        var rWan = p2p.evaluateCandidate("peer-wan", wanAddr, false, 85);
        assertEquals(P2pDirect.RouteMode.DIRECT_P2P, rWan.mode());
        assertTrue(p2p.isDirect("peer-wan"));

        // High latency probe falling back to relay
        var rSlow = p2p.evaluateCandidate("peer-slow", wanAddr, false, 320);
        assertEquals(P2pDirect.RouteMode.GATEWAY_RELAY, rSlow.mode());
        assertFalse(p2p.isDirect("peer-slow"));
    }
}
