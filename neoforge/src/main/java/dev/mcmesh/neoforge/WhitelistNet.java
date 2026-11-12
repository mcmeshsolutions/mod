package dev.mcmesh.neoforge;

import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Optional channel for the in-game whitelist editor; vanilla clients simply never see it. */
@EventBusSubscriber(modid = "mcmesh")
public final class WhitelistNet {
    /** Set by the client side so this class never touches client-only code. */
    public static volatile Consumer<State> onState;

    private WhitelistNet() { }

    public record State(boolean enabled, List<String> names) implements CustomPacketPayload {
        static final Type<State> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("mcmesh", "wl_state"));
        static final StreamCodec<ByteBuf, State> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, State::enabled,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(512)), State::names,
                State::new);

        @Override
        public Type<State> type() {
            return TYPE;
        }
    }

    public record Edit(String action, String name) implements CustomPacketPayload {
        static final Type<Edit> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("mcmesh", "wl_edit"));
        static final StreamCodec<ByteBuf, Edit> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Edit::action,
                ByteBufCodecs.STRING_UTF8, Edit::name,
                Edit::new);

        @Override
        public Type<Edit> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent e) {
        var r = e.registrar("1").optional();
        r.playToClient(State.TYPE, State.CODEC, (p, ctx) -> {
            var h = onState;
            if (h != null) {
                h.accept(p);
            }
        });
        r.playToServer(Edit.TYPE, Edit.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer sp && MeshWhitelist.active()
                    && MeshOps.isOp(sp.getGameProfile().getName())) {
                apply(p);
                send(sp);
            }
        });
    }

    private static void apply(Edit p) {
        String n = p.name().strip();
        switch (p.action()) {
            case "add" -> {
                if (!n.isEmpty() && n.length() <= 16) {
                    MeshWhitelist.add(n);
                }
            }
            case "remove" -> MeshWhitelist.remove(n);
            case "on" -> MeshWhitelist.enabled(true);
            case "off" -> MeshWhitelist.enabled(false);
            default -> { }
        }
    }

    /** Sends the current list to a player who may edit it and has the mod. */
    public static void send(ServerPlayer sp) {
        if (sp.connection.hasChannel(State.TYPE)) {
            PacketDistributor.sendToPlayer(sp, new State(MeshWhitelist.enabled(), MeshWhitelist.list()));
        }
    }
}
