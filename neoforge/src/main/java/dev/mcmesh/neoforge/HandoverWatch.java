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
    private static int criticalStreak;
    private static boolean hinted;

    private HandoverWatch() { }

    static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty("mcmesh.batteryhandover", "on"));
    }

    /** Called every 30 seconds while hosting, on the server thread. */
    static void check(MinecraftServer server, MeshService s) {
        if (!enabled() || server.isDedicatedServer()) {
            return;
        }
        var decision = HandoverAdvisor.decide(BatteryProbe.read());
        criticalStreak = decision == HandoverAdvisor.Decision.HAND_OVER_NOW ? criticalStreak + 1 : 0;
        if (decision == HandoverAdvisor.Decision.PREFER_HANDOVER && !hinted) {
            hinted = true;
            server.getPlayerList().broadcastSystemMessage(Component.literal(
                    "The host's battery is low. Plug the host machine in, or hosting will move to another player."), false);
        }
        if (criticalStreak < 2 || server.getPlayerCount() < 2) {
            return;
        }
        LOG.warn("host battery critical, handing the world over");
        s.markHandedOff();
        server.getPlayerList().broadcastSystemMessage(Component.literal(
                "The host's battery is almost empty. Hosting is moving to another player, you will be reconnected automatically."), false);
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
