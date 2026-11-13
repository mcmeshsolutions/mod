package dev.mcmesh.neoforge;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@EventBusSubscriber(modid = "mcmesh")
public final class MeshEvents {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int AUTOSAVE_TICKS = 20 * 120;

    private MeshEvents() { }

    @SubscribeEvent
    public static void started(ServerStartedEvent e) {
        MeshService s = MeshBoot.held();
        if (s == null) {
            return;
        }
        MinecraftServer server = e.getServer();
        String founder = server.isDedicatedServer() ? MeshConfig.PLAYER_NAME.get() : null;
        if (!server.isDedicatedServer()) {
            try {
                founder = MeshAuth.cached().name();
            } catch (Exception ex) {
                LOG.warn("no identity to seed the operator list with: {}", ex.toString());
            }
        }
        MeshOps.load(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize(), founder);
        MeshWhitelist.load(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize());
        s.onFence(() -> server.execute(() -> server.halt(false)));
        s.onRefused(() -> server.execute(() -> server.getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal(
                "The gateway refused the last save of this world (sanity check). Nothing more is uploaded this session. "
                        + "The world on the host's machine is kept as a separate copy; ask the gateway operator to look at it."), false)));
        s.onHandoff(name -> server.execute(() -> {
            server.getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal(
                    name + " wants to play together. Hosting is moving to their machine, you will be reconnected automatically."), false);
            Runnable go = () -> server.execute(() -> server.halt(false));
            var warn = MeshService.handoffWarning;
            if (warn != null) {
                warn.accept(name, go);
                return;
            }
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(8_000);
                } catch (InterruptedException ex) {
                    return;
                }
                go.run();
            });
        }));
        Thread.ofVirtual().start(() -> {
            try {
                if (!server.isDedicatedServer()) {
                    // guests arrive through the tunnel on loopback; the host's own client stays on the in-memory connection
                    dev.mcmesh.neoforge.client.HostFlow.publish(server);
                }
                s.activate();
            } catch (Exception ex) {
                LOG.error("mesh activate failed", ex);
            }
        });
    }

    // MinecraftServerMixin hides the singleplayer profile, so vanilla no longer stops the server when the host leaves
    @SubscribeEvent
    public static void hostLeft(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) {
        if (MeshBoot.held() != null && e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p
                && p.connection.getConnection().isMemoryConnection()) {
            p.server.getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal(
                    "The host left. Hosting moves to another eligible player automatically."), false);
            p.server.halt(false);
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post e) {
        MeshService s = MeshService.current();
        if (s != null && e.getServer().getTickCount() % AUTOSAVE_TICKS == 0) {
            // vanilla writes dirty chunks only every 5 minutes, and the mesh only sees region writes, so force them out first
            e.getServer().saveAllChunks(true, true, false);
            Thread.ofVirtual().start(() -> {
                try {
                    s.autosave();
                } catch (Exception ex) {
                    LOG.error("autosave failed", ex);
                }
            });
        }
    }

    @SubscribeEvent
    public static void loadShedding(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 20 != 0) {
            return;
        }
        if (MeshService.current() != null) {
            LoadShed.sample(e.getServer());
        } else {
            LoadShed.restore(e.getServer());
        }
    }

    private static int lowStreak;

    @SubscribeEvent
    public static void memory(ServerTickEvent.Post e) {
        MeshService s = MeshService.current();
        if (s == null || e.getServer().getTickCount() % (20 * 15) != 0) {
            return;
        }
        String why = MemWatch.pressure();
        lowStreak = why == null ? 0 : lowStreak + 1;
        if (lowStreak < 2) {
            return;
        }
        LOG.error("memory pressure on the host ({}), saving the world now", why);
        e.getServer().saveAllChunks(true, true, false);
        if (lowStreak == 2) {
            e.getServer().getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal(
                    "Host machine is low on memory (" + why + "). The world was saved early; close other programs or lower render settings."), false);
        }
        Thread.ofVirtual().start(() -> {
            try {
                s.emergencySave();
            } catch (Exception ex) {
                LOG.error("emergency save failed", ex);
            }
        });
    }

    @SubscribeEvent
    public static void stopping(ServerStoppedEvent e) {
        LoadShed.restore(e.getServer());
        MeshService s = MeshBoot.held();
        MeshBoot.clear();
        MeshOps.unload();
        MeshWhitelist.unload();
        if (s != null) {
            s.shutdown();
        }
    }
}
