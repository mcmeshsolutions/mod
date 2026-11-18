package dev.mcmesh.core;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CasSwarmTest {

    @Test
    void selectPeerSourcesWithLanPreference() {
        CasSwarm swarm = new CasSwarm();
        swarm.registerPeer("wan-peer", false);
        swarm.registerPeer("lan-peer", true);

        String blobA = "sha256:aaaa";
        String blobB = "sha256:bbbb";

        // WAN peer has blobA
        swarm.announceBlobs("wan-peer", Set.of(blobA));
        // Both have blobB
        swarm.announceBlobs("wan-peer", Set.of(blobB));
        swarm.announceBlobs("lan-peer", Set.of(blobB));

        // For blobA, only wan-peer is available
        var srcA = swarm.selectBestSource(blobA);
        assertNotNull(srcA);
        assertEquals("wan-peer", srcA.peerId());

        // For blobB, lan-peer is preferred
        var srcB = swarm.selectBestSource(blobB);
        assertNotNull(srcB);
        assertEquals("lan-peer", srcB.peerId());
        assertTrue(srcB.isLan());

        // Missing blob returns null (fallback to gateway)
        assertNull(swarm.selectBestSource("sha256:unknown"));
    }
}
