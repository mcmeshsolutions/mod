package dev.mcmesh.neoforge;

import dev.mcmesh.core.AnchorDuty;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands the world back when an anchor has had nobody on it for a while, so the machine can sit idle until the next
 * player turns up. The normal stop path commits and releases the lease, and systemd starts the server again into
 * standby, where it waits for demand.
 */
final class AnchorIdle {
    private static final Logger LOG = LoggerFactory.getLogger("mcmesh");
    private static long emptySince;
    private static boolean leaving;

    private AnchorIdle() { }

    /** Called once a second while hosting, on the server thread. */
    static void check(MinecraftServer server) {
        if (leaving || !server.isDedicatedServer() || !MeshConfig.ANCHOR_STANDBY.get()
                || !MeshConfig.ANCHOR_WAKE_ON_JOIN.get() || MeshService.current() == null) {
            return;
        }
        int players = server.getPlayerCount();
        long now = System.currentTimeMillis();
        if (players > 0) {
            emptySince = 0;
            return;
        }
        if (emptySince == 0) {
            emptySince = now;
            return;
        }
        if (!AnchorDuty.shouldRelease(true, players, now - emptySince, MeshConfig.ANCHOR_IDLE_MINUTES.get())) {
            return;
        }
        leaving = true;
        LOG.info("anchor idle for {} minutes, saving and handing the world back", MeshConfig.ANCHOR_IDLE_MINUTES.get());
        server.execute(() -> server.halt(false));
    }
}
