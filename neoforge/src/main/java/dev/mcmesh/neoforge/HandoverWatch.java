package dev.mcmesh.neoforge;

import dev.mcmesh.core.BatteryProbe;
import dev.mcmesh.core.HandoverAdvisor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Gives the world away before a laptop host dies, but only when another player is online to take over. */
final class HandoverWatch {
    private static final Logger LOG = LoggerFactory.getLogger("mcmesh");
    private static final long PROBE_EVERY_MS = 10 * 60_000L;
    private static volatile double uploadMbps = -1;
    private static volatile long lastProbe;
    private static volatile boolean probing;
    private static int criticalStreak;
    private static boolean hinted;

    private HandoverWatch() { }

    /** Last measured uplink in Mbit/s, or -1. */
    static double uploadMbps() {
        return uploadMbps;
    }

    static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty("mcmesh.batteryhandover", "on"));
    }

    /** Off the server thread: one probe upload every ten minutes, the first right away. */
    private static void probeLinkIfDue(MeshService s) {
        long now = System.currentTimeMillis();
        if (probing || now - lastProbe < PROBE_EVERY_MS) {
            return;
        }
        probing = true;
        lastProbe = now;
        Thread.ofVirtual().name("mesh-bandwidth").start(() -> {
            try {
                uploadMbps = s.probeUploadMbps();
                LOG.info("upload probe: {} Mbit/s", uploadMbps < 0 ? "failed" : String.format("%.1f", uploadMbps));
            } finally {
                probing = false;
            }
        });
    }

    /**
     * The machine is suspending or shutting down and logind gives us only a few seconds (InhibitDelayMaxSec).
     * Flush the WAL barrier first, because that is the part that must finish inside the grace; the normal stop with its
     * full world save is started afterwards and may well be cut short by the suspend, in which case the gateway recovers
     * from the barrier. Runs on a virtual thread, called by SleepWatch.
     */
    private static void powerDown(MinecraftServer server, MeshService s) {
        try {
            s.checkpoint();
        } catch (Exception e) {
            LOG.warn("checkpoint before suspend failed: {}", e.toString());
        }
        if (server.getPlayerCount() < 2) {
            return;
        }
        s.markHandedOff();
        server.execute(() -> {
            server.getPlayerList().broadcastSystemMessage(Component.literal(
                    "The host machine is going to sleep. Hosting is moving to another player, you will be reconnected automatically."), false);
            server.halt(false);
        });
    }

    /** Called every 30 seconds while hosting, on the server thread. */
    static void check(MinecraftServer server, MeshService s) {
        if (!enabled() || server.isDedicatedServer()) {
            return;
        }
        probeLinkIfDue(s);
        SleepWatch.start(() -> powerDown(server, s));
        var decision = HandoverAdvisor.decide(BatteryProbe.read(), uploadMbps);
        criticalStreak = decision == HandoverAdvisor.Decision.HAND_OVER_NOW ? criticalStreak + 1 : 0;
        if (decision == HandoverAdvisor.Decision.PREFER_HANDOVER && !hinted) {
            hinted = true;
            server.getPlayerList().broadcastSystemMessage(Component.literal(
                    "The host machine is struggling (low battery or a slow connection). Plug it in or check the network, or hosting will move to another player."), false);
        }
        if (criticalStreak < 2 || server.getPlayerCount() < 2) {
            return;
        }
        LOG.warn("host battery or uplink critical, handing the world over");
        s.markHandedOff();
        server.getPlayerList().broadcastSystemMessage(Component.literal(
                "The host machine can no longer keep up (empty battery or a very slow connection). Hosting is moving to another player, you will be reconnected automatically."), false);
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(8_000);
            } catch (InterruptedException ex) {
                return;
            }
            server.execute(() -> server.halt(false));
        });
        criticalStreak = 0;
    }
}
