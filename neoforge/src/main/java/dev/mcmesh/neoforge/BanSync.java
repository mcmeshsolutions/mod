package dev.mcmesh.neoforge;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import dev.mcmesh.core.GatewayClient;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Keeps the host's own ban list in step with the world's bans on the gateway. The gateway already refuses tickets to banned
 * players; this covers a banned player who reaches the host by another route and keeps the in-game /banlist honest.
 * Entries this mod added carry the source "mcmesh" and are the only ones it ever removes.
 */
final class BanSync {
    private static final Logger LOG = LogUtils.getLogger();
    static final String SOURCE = "mcmesh";
    private static volatile Set<String> names = Set.of();

    private BanSync() { }

    static boolean isBanned(String name) {
        return names.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Fetches off the server thread, then applies on it. */
    static void refresh(MinecraftServer server, MeshService s) {
        Thread.ofVirtual().start(() -> {
            List<GatewayClient.Banned> list;
            try {
                list = s.gateway().banned(s.worldId());
            } catch (Exception e) {
                LOG.warn("could not read the world bans: {}", e.toString());
                return;
            }
            server.execute(() -> apply(server, list));
        });
    }

    static void apply(MinecraftServer server, List<GatewayClient.Banned> list) {
        UserBanList bans = server.getPlayerList().getBans();
        LOG.debug("world bans from the gateway: {}", list.size());
        Set<String> pushed = loadPushed();
        Set<String> lower = new HashSet<>();
        Set<String> wanted = new HashSet<>();
        for (GatewayClient.Banned b : list) {
            lower.add(b.name().toLowerCase(Locale.ROOT));
            for (UUID id : new UUID[] {UUID.fromString(b.uuid()), UUIDUtil.createOfflinePlayerUUID(b.name())}) {
                GameProfile p = new GameProfile(id, b.name());
                String rec = id + "|" + b.name();
                if (pushed.contains(rec)) {
                    wanted.add(rec);
                }
                if (!bans.isBanned(p)) {
                    wanted.add(rec);
                    bans.add(new UserBanListEntry(p, null, SOURCE, null, "banned from this world on the mesh gateway"));
                    LOG.info("world ban applied to the host ban list: {}", b.name());
                }
            }
        }
        for (String old : pushed) {
            if (!wanted.contains(old)) {
                String[] parts = old.split("\\|", 2);
                bans.remove(new GameProfile(UUID.fromString(parts[0]), parts[1]));
                LOG.info("world ban lifted from the host ban list: {}", parts[1]);
            }
        }
        if (!pushed.equals(wanted)) {
            savePushed(wanted);
        }
        names = lower;
        for (ServerPlayer pl : List.copyOf(server.getPlayerList().getPlayers())) {
            kickIfBanned(pl);
        }
    }

    /** What this machine has put into its ban list: only these are ever removed again. Per host, not per world folder, because banned-players.json is per server. */
    private static java.nio.file.Path pushedFile() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("mcmesh-pushed-bans.json");
    }

    private static Set<String> loadPushed() {
        try {
            java.nio.file.Path f = pushedFile();
            if (!java.nio.file.Files.isRegularFile(f)) {
                return new HashSet<>();
            }
            Set<String> out = new HashSet<>();
            com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonArray().forEach(e -> out.add(e.getAsString()));
            return out;
        } catch (Exception e) {
            LOG.warn("unreadable pushed ban record, starting empty: {}", e.toString());
            return new HashSet<>();
        }
    }

    private static void savePushed(Set<String> s) {
        try {
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            s.forEach(a::add);
            java.nio.file.Files.writeString(pushedFile(), a.toString());
        } catch (java.io.IOException e) {
            LOG.warn("could not record the pushed bans: {}", e.toString());
        }
    }

    static void kickIfBanned(ServerPlayer pl) {
        if (isBanned(pl.getGameProfile().getName())) {
            pl.connection.disconnect(Component.literal("You are banned from this world."));
        }
    }
}
