package dev.mcmesh.neoforge.client;

import dev.mcmesh.neoforge.MeshBoot;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Save and Quit on a hosting machine: guests get a countdown before the world leaves, Alt+F4 skips it and relies on the crash path. */
public final class HostExit {
    private static final int SECONDS = 10;
    private static volatile boolean bypass;
    private static volatile boolean counting;

    private HostExit() {}

    /** Explicit handover from pause menu button ensuring 0-disconnect host departure. */
    public static void handOverAndLeave(net.minecraft.client.gui.screens.Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null && server.getPlayerCount() >= 2) {
            intercept();
        } else {
            mc.level.disconnect();
            mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
            mc.setScreen(new TitleScreen());
        }
    }

    /** True when the quit was held back for the countdown. */
    public static boolean intercept() {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (bypass || counting || server == null || MeshBoot.held() == null || server.getPlayerCount() < 2) {
            return false;
        }
        counting = true;
        ScheduledExecutorService tick = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mesh-exit-countdown");
            t.setDaemon(true);
            return t;
        });
        broadcast(server, true, "The host is leaving. The world moves to another player in " + SECONDS + " seconds.");
        long[] left = {SECONDS};
        tick.scheduleAtFixedRate(() -> {
            long n = --left[0];
            if (n > 0) {
                server.execute(() -> actionBar(server, "Host leaving in " + n + " s"));
            }
        }, 1, 1, TimeUnit.SECONDS);
        HandoverScreen screen = new HandoverScreen("Handing the world over",
                (server.getPlayerCount() - 1) + " player(s) are told and move to a new host.", SECONDS * 1000L, () -> {
                    tick.shutdownNow();
                    counting = false;
                    bypass = true;
                    try {
                        mc.level.disconnect();
                        mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
                        mc.setScreen(new TitleScreen());
                    } finally {
                        bypass = false;
                    }
                });
        screen.onCancel(() -> {
            tick.shutdownNow();
            counting = false;
            server.execute(() -> broadcast(server, false, "The host stayed. Carry on."));
            mc.setScreen(null);
        });
        mc.setScreen(screen);
        return true;
    }

    private static void broadcast(MinecraftServer server, boolean leaving, String text) {
        server.execute(() -> {
            server.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.connection.getConnection().isMemoryConnection()) {
                    continue;
                }
                if (leaving) {
                    p.connection.send(new ClientboundSetTitlesAnimationPacket(5, 80, 10));
                    p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Hosting moves to another player automatically")));
                    p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("The host is leaving")));
                } else {
                    p.connection.send(new ClientboundSetActionBarTextPacket(Component.empty()));
                }
            }
        });
    }

    private static void actionBar(MinecraftServer server, String text) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.connection.getConnection().isMemoryConnection()) {
                p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(text)));
            }
        }
    }
}
