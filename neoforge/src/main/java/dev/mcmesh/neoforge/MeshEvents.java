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

    private static final dev.mcmesh.core.QuotaNotice QUOTA = new dev.mcmesh.core.QuotaNotice();

    private MeshEvents() { }

    @SubscribeEvent
    public static void compatScan(ServerStartedEvent e) {
        CompatRegistry.scan();
    }

    @SubscribeEvent
    public static void tileWorkerStart(ServerStartedEvent e) {
        if (TileWorker.enabled() && e.getServer().isDedicatedServer()) {
            TileWorker.start(e.getServer());
        }
    }

    @SubscribeEvent
    public static void islandProbeStart(ServerStartedEvent e) {
        IslandProbe.start(e.getServer());
    }

    @SubscribeEvent
    public static void islandProbeSample(ServerTickEvent.Post e) {
        IslandProbe.sample(e.getServer());
    }

    @SubscribeEvent
    public static void debugMapPush(ServerTickEvent.Post e) {
        DebugNet.tick(e.getServer());
    }

    @SubscribeEvent
    public static void anchorIdle(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 20 == 0) {
            AnchorIdle.check(e.getServer());
        }
    }

    @SubscribeEvent
    public static void debugMapForget(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) {
            DebugNet.forget(p);
        }
    }

    @SubscribeEvent
    public static void playerLoginDigest(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) {
            long prev = dev.mcmesh.core.WorldDigest.recordPlayerLogin(p.getUUID());
            long now = System.currentTimeMillis() / 1000L;
            if (prev > 0 && (now - prev) >= 3600L) {
                long hours = (now - prev) / 3600L;
                p.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7b[MCMesh]\u00a7r Welcome back! While you were offline (" + hours + "h), your team made changes. Run \u00a7e/meshdigest\u00a7r to view world patch notes."
                ));
            }
        }
    }

    @SubscribeEvent
    public static void playerNameFormat(net.neoforged.neoforge.event.entity.player.PlayerEvent.NameFormat e) {
        if (Scratchpad.isInScratchpad(e.getEntity().getUUID())) {
            String timer = Scratchpad.getTimerText(e.getEntity().getUUID());
            e.setDisplayname(e.getDisplayname().copy().append(net.minecraft.network.chat.Component.literal(" \u00a7b[" + timer + "]")));
        }
    }

    @SubscribeEvent
    public static void onBlockPlaced(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent e) {
        if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) {
            if (Scratchpad.isInScratchpad(p.getUUID())) {
                String blockId = String.valueOf(e.getPlacedBlock().getBlock());
                Scratchpad.recordBlockPlaced(p.getUUID(), blockId);
            }
        }
    }

    @SubscribeEvent
    public static void onBlockBroken(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent e) {
        if (e.getPlayer() instanceof net.minecraft.server.level.ServerPlayer p) {
            if (Scratchpad.isInScratchpad(p.getUUID())) {
                String blockId = String.valueOf(e.getState().getBlock());
                Scratchpad.recordBlockBroken(p.getUUID(), blockId);
            }
        }
    }

    @SubscribeEvent
    public static void pregenSwarmTick(ServerTickEvent.Post e) {
        if (LoadShed.isFpsThrottled()) {
            return;
        }
        if (e.getServer().getTickCount() % 10 == 0) {
            double mspt = e.getServer().getAverageTickTimeNanos() / 1_000_000.0;
            double tps = mspt <= 50.0 ? 20.0 : 1000.0 / mspt;
            var next = MeshCommands.PREGEN_SWARM.pollNext(tps, 19.5);
            if (next != null) {
                var overworld = e.getServer().overworld();
                if (overworld != null) {
                    overworld.getChunkSource().getChunk(next.cx(), next.cz(), net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY, true);
                }
            }
        }
    }

    @SubscribeEvent
    public static void inventoryTrack(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) {
            var inv = p.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                var stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    MeshCommands.INVENTORY_LEDGER.recordDelta(p.getUUID(), i, stack.getItem().toString(), stack.getCount());
                }
            }
        }
    }

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
        MeshApiImpl.restoreAll(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize());
        BanSync.refresh(server, s);
        s.onFence(() -> server.execute(() -> server.halt(false)));
        s.onRefused(() -> server.execute(() -> server.getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal(
                "The gateway refused the last save of this world (sanity check). Nothing more is uploaded this session. "
                        + "The world on the host's machine is kept as a separate copy; ask the gateway operator to look at it."), false)));
        s.onHandoff(name -> server.execute(() -> {
            server.getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal(
                    name + " wants to play together. Hosting is moving to their machine, you will be reconnected automatically."), false);
            Runnable go = () -> Thread.ofVirtual().start(() -> {
                s.prepareHandover(server, 30_000);
                server.execute(() -> server.halt(false));
            });
            var warn = MeshService.handoffWarning;
            if (warn != null) {
                warn.accept(name, go);
                return;
            }
            go.run();
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
    /** halt() removes every player, which fires this very event again for the host; without the guard that recurses until the stack overflows and the final save never happens. */
    private static final java.util.concurrent.atomic.AtomicBoolean HALTING = new java.util.concurrent.atomic.AtomicBoolean();

    @SubscribeEvent
    public static void hostLeft(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) {
        if (MeshBoot.held() != null && e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p
                && p.connection.getConnection().isMemoryConnection() && HALTING.compareAndSet(false, true)) {
            try {
                p.server.getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal(
                        "The host left. Hosting moves to another eligible player automatically."), false);
                p.server.halt(false);
            } finally {
                HALTING.set(false);
            }
        }
    }

    private static volatile dev.mcmesh.core.ChatRetention chat;

    @SubscribeEvent
    public static void chatStart(ServerStartedEvent e) {
        int days = MeshConfig.CHAT_RETENTION_DAYS.get();
        chat = new dev.mcmesh.core.ChatRetention(e.getServer().getServerDirectory().resolve("logs/mesh-chat"), days);
        try {
            int gone = chat.prune(java.time.Instant.now());
            if (gone > 0) {
                LOG.info("chat retention: removed {} day files older than {} days", gone, days);
            }
        } catch (java.io.IOException ex) {
            LOG.warn("chat retention: could not prune: {}", ex.toString());
        }
    }

    static final dev.mcmesh.core.ChatBuffer RECENT = new dev.mcmesh.core.ChatBuffer(100);

    @SubscribeEvent
    public static void chatKeep(net.neoforged.neoforge.event.ServerChatEvent e) {
        if (MeshConfig.REPORT_CHAT_LINES.get() > 0 && MeshService.current() != null) {
            RECENT.add(java.time.Instant.now(), e.getUsername(), e.getRawText());
        }
        var c = chat;
        if (c == null || MeshService.current() == null) {
            return;
        }
        try {
            c.record(java.time.Instant.now(), e.getUsername(), e.getRawText());
        } catch (java.io.IOException ex) {
            LOG.warn("chat retention: could not write: {}", ex.toString());
        }
    }

    private static final java.util.Map<java.util.UUID, int[]> LAST_CHUNK = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> LAST_WAIT_NOTICE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long WAIT_NOTICE_COOLDOWN_MS = 15_000;

    @SubscribeEvent
    public static void lazyPrefetch(ServerTickEvent.Post e) {
        LazyWorld w = LazyWorld.active;
        if (w == null || e.getServer().getTickCount() % 40 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        var pending = w.pendingKeys();
        for (var p : e.getServer().getPlayerList().getPlayers()) {
            LAZY_TRACK.update(p.getStringUUID(), p.level().dimension().location().toString(), p.getX(), p.getZ(), now);
            // the guard blocks the region read itself, so this is advance notice rather than the only signal
            String here = dev.mcmesh.core.LazyPlan.regionKey(p.level().dimension().location().toString(),
                    (int) Math.floor(p.getX()) >> 4, (int) Math.floor(p.getZ()) >> 4);
            if (pending.contains(here)) {
                Long last = LAST_WAIT_NOTICE.get(p.getUUID());
                if (last == null || now - last > WAIT_NOTICE_COOLDOWN_MS) {
                    LAST_WAIT_NOTICE.put(p.getUUID(), now);
                    p.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "[mesh] Fetching the world near you from the gateway, this may stall the game briefly."));
                }
            }
        }
        // regions in the order the players will reach them, as far ahead as it takes to fetch a few of them
        double loadBlocks = Math.max(e.getServer().getPlayerList().getViewDistance(), e.getServer().getPlayerList().getSimulationDistance()) * 16.0 + 32;
        w.prefetch(dev.mcmesh.core.LazyPlan.byArrival(LAZY_TRACK.movers(), pending, loadBlocks, w.horizonS()));
    }

    private static final dev.mcmesh.core.Arrival.Track LAZY_TRACK = new dev.mcmesh.core.Arrival.Track();

    @SubscribeEvent
    public static void tileSample(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 100 == 0 && MeshService.current() != null) {
            TileHost.sample(e.getServer());
        }
    }

    @SubscribeEvent
    public static void banOnLogin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) {
            if (MeshService.current() != null) {
                BanSync.kickIfBanned(p);
            }
            enforceGuestViewDistance(p);
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post e) {
        MeshService s = MeshService.current();
        if (s != null && e.getServer().getTickCount() % (20 * MeshConfig.AUTOSAVE_SECONDS.get()) == 0) {
            // vanilla writes dirty chunks only every 5 minutes, and the mesh only sees region writes, so force them out first
            e.getServer().saveAllChunks(true, true, false);
            Thread.ofVirtual().start(() -> {
                try {
                    s.autosave();
                } catch (Exception ex) {
                    if (dev.mcmesh.core.QuotaNotice.isQuota(ex)) {
                        String note = QUOTA.message(ex, System.currentTimeMillis());
                        if (note != null) {
                            LOG.warn(note);
                            e.getServer().execute(() -> e.getServer().getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal(note), false));
                        }
                    } else {
                        LOG.error("autosave failed", ex);
                    }
                }
            });
        }
    }

    @SubscribeEvent
    public static void modTasks(ServerTickEvent.Post e) {
        if (MeshApiImpl.TICKS.size() > 0) {
            MeshApiImpl.TICKS.run(dev.mcmesh.core.BudgetedQueue.budgetMicros(LoadShed.reportedLevel()));
        }
    }

    @SubscribeEvent
    public static void stream(ServerTickEvent.Post e) {
        int every = MeshConfig.STREAM_SECONDS.get();
        MeshService s = MeshService.current();
        if (every <= 0 || s == null || !MeshService.safeMode().streaming()
                || e.getServer().getTickCount() % (20 * every) != 0) {
            return;
        }
        e.getServer().saveAllChunks(true, true, false);
        Thread.ofVirtual().start(() -> {
            try {
                s.checkpoint();
            } catch (Exception ex) {
                if (!dev.mcmesh.core.QuotaNotice.isQuota(ex)) {
                    LOG.warn("streaming checkpoint failed: {}", ex.toString());
                }
            }
        });
    }

    public static void enforceGuestViewDistance(net.minecraft.server.level.ServerPlayer p) {
        if (p.connection != null && p.connection.getConnection() != null && !p.connection.getConnection().isMemoryConnection()) {
            int maxCap = MeshConfig.MAX_GUEST_VIEW_DISTANCE.get();
            if (p.requestedViewDistance() > maxCap) {
                var info = p.clientInformation();
                p.updateOptions(new net.minecraft.server.level.ClientInformation(
                        info.language(), maxCap, info.chatVisibility(), info.chatColors(),
                        info.modelCustomisation(), info.mainHand(), info.textFilteringEnabled(), info.allowsListing()
                ));
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket(maxCap));
            }
        }
    }

    @SubscribeEvent
    public static void loadShedding(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 20 != 0) {
            return;
        }
        MeshService hosting = MeshService.current();
        if (hosting != null) {
            for (var p : e.getServer().getPlayerList().getPlayers()) {
                enforceGuestViewDistance(p);
            }
            LoadShed.countPlayers(e.getServer());
            LoadShed.sample(e.getServer());
            if (e.getServer().getTickCount() % 600 == 0) {
                HandoverWatch.check(e.getServer(), hosting);
            }
            if (e.getServer().getTickCount() % 1200 == 0) {
                BanSync.refresh(e.getServer(), hosting);
            }
            if (e.getServer().getTickCount() % 600 == 300) {
                TileHost.tick(e.getServer(), hosting);
            }
            if (e.getServer().getTickCount() % 200 == 100 && MeshConfig.SHARD_OFFERS.get()) {
                ShardMain.watch(e.getServer(), hosting);
            }
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

    /** The server has begun its own stop (saving chunks next); the JVM exit hook must wait for it before the final commit. */
    @SubscribeEvent
    public static void stopBegun(net.neoforged.neoforge.event.server.ServerStoppingEvent e) {
        MeshService s = MeshBoot.held();
        if (s != null) {
            s.serverStopping();
        }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppedEvent e) {
        IslandProbe.stop();
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
