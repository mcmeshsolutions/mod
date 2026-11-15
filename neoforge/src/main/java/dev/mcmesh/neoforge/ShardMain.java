package dev.mcmesh.neoforge;

import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.core.LazyPlan;
import dev.mcmesh.core.RevisionFiles;
import dev.mcmesh.core.ShardRegion;
import dev.mcmesh.core.ShardRules;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The whole-world host's side of multi-host (ROADMAP "Dynamic multi-host sharding", board/RESEARCH.md 7): give regions to a machine that
 * waits as a shard host, keep track of what is away, and take regions and players back when a shard ends.
 *
 * Giving: the players inside are moved first (told, saved, disconnected, named in the offer so the relay sends them to the shard), the
 * regions are saved and uploaded in a last commit, this host stops uploading them, and only then is the offer made, so whoever takes it
 * starts from this host's final state of those regions.
 * Taking back: the shard's regions come from the head into a lazy fetch that blocks the game from reading them until they are replaced,
 * the players' files come down at once, and only then does the gateway let this host write there again.
 */
final class ShardMain {
    private static final Logger LOG = LoggerFactory.getLogger("mcmesh");
    private static volatile boolean busy;

    private ShardMain() { }

    /** What `/meshshard give` does; the answer is what the operator sees. Runs on its own thread: it waits for the network and for saves. */
    static void give(MinecraftServer server, MeshService s, String dim, int x1, int z1, int x2, int z2, java.util.function.Consumer<String> say) {
        if (busy) {
            say.accept("a shard operation is already running");
            return;
        }
        busy = true;
        Thread.ofVirtual().name("mesh-shard-give").start(() -> {
            try {
                say.accept(run(server, s, dim, x1, z1, x2, z2));
            } catch (Exception e) {
                LOG.error("giving regions away failed", e);
                say.accept("failed: " + e);
            } finally {
                busy = false;
            }
        });
    }

    private static String run(MinecraftServer server, MeshService s, String dim, int x1, int z1, int x2, int z2) throws Exception {
        if (s.isShard()) {
            return "a shard host cannot give regions away";
        }
        GatewayClient gw = s.gateway();
        String world = s.worldId();
        ShardRegion region = new ShardRegion(dim, Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
        var view = gw.shards(world);
        if (view.standby() < 1) {
            return "no machine is waiting as a shard host (shardStandby = true on a dedicated server of this world)";
        }
        // the players who are in these regions now
        List<String> uuids = new ArrayList<>();
        List<Runnable> kicks = new ArrayList<>();
        server.submit(() -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.level().dimension().location().toString().equals(dim) && region.containsChunk(dim, p.chunkPosition().x, p.chunkPosition().z)) {
                    uuids.add(p.getStringUUID());
                    kicks.add(() -> p.connection.disconnect(Component.literal("Your part of the world is moving to another host. Rejoin in a few seconds.")));
                    p.sendSystemMessage(Component.literal("This part of the world is moving to another host, you will be disconnected in a moment and can rejoin."));
                }
            }
        }).join();
        // the last state of the regions goes up while they are still ours
        server.submit(() -> server.saveAllChunks(true, true, false)).join();
        s.autosave();
        List<ShardRegion> away = new ArrayList<>(Ownership.away());
        away.add(region);
        Ownership.giveAway(away);
        long offer;
        try {
            offer = gw.shardOffer(world, List.of(region), uuids);
        } catch (Exception e) {
            // nothing was given: take the regions back before anything else is uploaded
            away.remove(region);
            Ownership.giveAway(away);
            throw e;
        }
        server.submit(() -> kicks.forEach(Runnable::run)).join();
        LOG.info("offered regions {}..{} x {}..{} of {} (offer {}), {} players moved", region.x1(), region.x2(), region.z1(), region.z2(), dim, offer, uuids.size());
        for (int i = 0; i < 60; i++) {
            Thread.sleep(1_000);
            for (var sh : gw.shards(world).shards()) {
                if (sh.live() && sh.regions().contains(region)) {
                    return "regions given to shard " + sh.id() + ", " + uuids.size() + " players moved";
                }
            }
        }
        return "offered, but no shard host has taken the regions yet: players who were moved wait until one does";
    }

    /** Every ten seconds on the whole-world host: what is away, and what has come back. */
    static void watch(MinecraftServer server, MeshService s) {
        if (s.isShard() || busy) {
            return;
        }
        busy = true;
        Thread.ofVirtual().name("mesh-shard-watch").start(() -> {
            try {
                tick(s);
            } catch (Exception e) {
                LOG.debug("shard watch: {}", e.toString());
            } finally {
                busy = false;
            }
        });
    }

    private static void tick(MeshService s) throws Exception {
        GatewayClient gw = s.gateway();
        String world = s.worldId();
        var view = gw.shards(world);
        Set<ShardRegion> away = new LinkedHashSet<>(view.offered());
        for (var sh : view.shards()) {
            away.addAll(sh.regions());
        }
        // anything this host gave away that the gateway no longer lists is already back; what the gateway lists as ended is taken back now
        for (var sh : view.shards()) {
            if (!sh.live() && takeBack(gw, s, sh)) {
                away.removeAll(sh.regions());
            }
        }
        Ownership.giveAway(new ArrayList<>(away));
    }

    private static boolean takeBack(GatewayClient gw, MeshService s, GatewayClient.ShardLive shard) throws Exception {
        Path root = s.worldRoot();
        String world = s.worldId();
        String head = gw.revisionIds(world, 1).get(0);
        RevisionFiles files = gw.listRevision(head);
        // the players' files first: a player who logs in before they are down would load the old one and overwrite the new
        WorldSync.Sink sink = new WorldSync.Sink(root);
        try {
            List<RevisionFiles.Aux> mine = new ArrayList<>();
            for (RevisionFiles.Aux a : files.aux()) {
                if (ShardRules.auxAllowed(a.path()) && shard.players().stream().anyMatch(u -> a.path().contains(u))) {
                    mine.add(a);
                }
            }
            dev.mcmesh.core.WorldDownloader.downloadParts(gw, List.of(), mine, sink, SyncProgress::update);
        } finally {
            sink.close();
        }
        Map<String, List<RevisionFiles.Chunk>> pending = new LinkedHashMap<>();
        for (RevisionFiles.Chunk c : files.chunks()) {
            if (ShardRegion.any(shard.regions(), c.dim(), c.cx() >> 5, c.cz() >> 5)) {
                pending.computeIfAbsent(LazyPlan.regionKey(c.dim(), c.cx(), c.cz()), k -> new ArrayList<>()).add(c);
            }
        }
        if (!pending.isEmpty() && !LazyWorld.adopt(gw, root, head, pending, (int) (System.currentTimeMillis() / 1000) - 3600)) {
            return false;
        }
        gw.shardMerged(world, shard.id());
        LOG.info("shard {} is back: {} regions, {} players", shard.id(), pending.size(), shard.players().size());
        return true;
    }
}
