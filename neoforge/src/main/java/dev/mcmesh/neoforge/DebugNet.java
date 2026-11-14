package dev.mcmesh.neoforge;

import dev.mcmesh.core.IslandPlan;
import dev.mcmesh.core.WorldgenTiles;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Feeds the in-game spatial debug map: the island layout, the chunks still waiting on the gateway, and who anchors
 * what. Only operators may subscribe, because the snapshot gives away every player's position. Nothing is sent
 * until a client asks, and a client only asks while its map is open.
 */
@EventBusSubscriber(modid = "mcmesh")
public final class DebugNet {
    private static final int EVERY_TICKS = 20;
    private static final Set<UUID> watching = ConcurrentHashMap.newKeySet();

    /** Set by the client side so this class never touches client-only code. */
    public static volatile Consumer<Snapshot> onSnapshot;

    private DebugNet() { }

    /** kind 0 is a player, 1 a forced chunk. */
    public record Anchor(int x, int z, int island, int kind, String name) {
        static final StreamCodec<ByteBuf, Anchor> CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, Anchor::x,
                ByteBufCodecs.INT, Anchor::z,
                ByteBufCodecs.VAR_INT, Anchor::island,
                ByteBufCodecs.VAR_INT, Anchor::kind,
                ByteBufCodecs.STRING_UTF8, Anchor::name,
                Anchor::new);
    }

    /** Region coordinates, not chunk coordinates: a pending region is a whole 32 x 32 block of chunks. */
    public record Region(int x, int z) {
        static final StreamCodec<ByteBuf, Region> CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, Region::x,
                ByteBufCodecs.INT, Region::z,
                Region::new);
    }

    public record Snapshot(String dimension, List<Anchor> anchors, List<Region> pending, int contested)
            implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("mcmesh", "dbg_map"));
        static final StreamCodec<ByteBuf, Snapshot> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Snapshot::dimension,
                Anchor.CODEC.apply(ByteBufCodecs.list(512)), Snapshot::anchors,
                Region.CODEC.apply(ByteBufCodecs.list(4096)), Snapshot::pending,
                ByteBufCodecs.VAR_INT, Snapshot::contested,
                Snapshot::new);

        @Override
        public Type<Snapshot> type() {
            return TYPE;
        }
    }

    public record Watch(boolean on) implements CustomPacketPayload {
        public static final Type<Watch> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("mcmesh", "dbg_watch"));
        static final StreamCodec<ByteBuf, Watch> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, Watch::on, Watch::new);

        @Override
        public Type<Watch> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent e) {
        var r = e.registrar("1").optional();
        r.playToClient(Snapshot.TYPE, Snapshot.CODEC, (p, ctx) -> {
            Consumer<Snapshot> h = onSnapshot;
            if (h != null) {
                h.accept(p);
            }
        });
        r.playToServer(Watch.TYPE, Watch.CODEC, (p, ctx) -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) {
                return;
            }
            if (p.on() && MeshOps.isOp(sp.getGameProfile().getName())) {
                watching.add(sp.getUUID());
                send(sp);
            } else {
                watching.remove(sp.getUUID());
            }
        });
    }

    static void tick(MinecraftServer server) {
        if (watching.isEmpty() || server.getTickCount() % EVERY_TICKS != 0) {
            return;
        }
        // one snapshot per dimension, not per watcher: contested() walks every owned chunk and two ops in the
        // same world would otherwise pay for it twice
        java.util.Map<ServerLevel, Snapshot> perLevel = new java.util.HashMap<>();
        for (UUID id : watching) {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp == null) {
                watching.remove(id);
                continue;
            }
            if (!sp.connection.hasChannel(Snapshot.TYPE)) {
                continue;
            }
            PacketDistributor.sendToPlayer(sp, perLevel.computeIfAbsent(sp.serverLevel(), DebugNet::snapshot));
        }
    }

    static void forget(ServerPlayer sp) {
        watching.remove(sp.getUUID());
    }

    private static void send(ServerPlayer sp) {
        if (!sp.connection.hasChannel(Snapshot.TYPE)) {
            return;
        }
        PacketDistributor.sendToPlayer(sp, snapshot(sp.serverLevel()));
    }

    /** The island layout of one dimension, as the host would compute it for sharding. */
    static Snapshot snapshot(ServerLevel level) {
        String dim = level.dimension().location().toString();
        List<IslandPlan.Anchor> anchors = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            anchors.add(new IslandPlan.Anchor("player:" + p.getGameProfile().getName(),
                    p.blockPosition().getX() >> 4, p.blockPosition().getZ() >> 4));
        }
        var forced = level.getForcedChunks().iterator();
        while (forced.hasNext()) {
            long c = forced.nextLong();
            int cx = net.minecraft.world.level.ChunkPos.getX(c);
            int cz = net.minecraft.world.level.ChunkPos.getZ(c);
            anchors.add(new IslandPlan.Anchor("forced:" + cx + ":" + cz, cx, cz));
        }
        var islands = IslandPlan.of(anchors);

        List<Anchor> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            int cx = p.blockPosition().getX() >> 4;
            int cz = p.blockPosition().getZ() >> 4;
            out.add(new Anchor(cx, cz, Math.max(IslandPlan.ownerOf(islands, WorldgenTiles.pack(cx, cz)), 0), 0,
                    p.getGameProfile().getName()));
        }
        forced = level.getForcedChunks().iterator();
        while (forced.hasNext()) {
            long c = forced.nextLong();
            int cx = net.minecraft.world.level.ChunkPos.getX(c);
            int cz = net.minecraft.world.level.ChunkPos.getZ(c);
            out.add(new Anchor(cx, cz, Math.max(IslandPlan.ownerOf(islands, WorldgenTiles.pack(cx, cz)), 0), 1, ""));
        }

        List<Region> pending = new ArrayList<>();
        LazyWorld lazy = LazyWorld.active;
        if (lazy != null) {
            for (String key : lazy.pendingKeys()) {
                String[] parts = key.split("\\|");   // dim|rx|rz
                if (parts.length == 3 && parts[0].equals(dim)) {
                    pending.add(new Region(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                }
            }
        }
        return new Snapshot(dim, out, pending, IslandPlan.contested(islands).size());
    }
}
