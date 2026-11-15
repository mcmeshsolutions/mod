package dev.mcmesh.neoforge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcmesh.core.Bytes;
import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.core.GatewayException;
import dev.mcmesh.core.TileMerge;
import dev.mcmesh.core.WorldgenLedger;
import dev.mcmesh.core.WorldgenPlanner;
import dev.mcmesh.core.WorldgenTiles;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The host's half of distributed worldgen: asks the gateway for tiles ahead of the players, and installs the ones the
 * peers agreed on before the game has opened that region. Anything that goes wrong costs nothing but the tile: the game
 * generates a region that has no files exactly as it always did.
 */
final class TileHost {
    private static final Logger LOG = LoggerFactory.getLogger("mcmesh");
    private static final List<String> KINDS = List.of("region", "entities", "poi");
    private static final int REGIONS_AHEAD = 4;
    private static final int SKIP_NEAR = 0;
    private static final int PER_ROUND = 8;
    private static final int FETCHERS = 6;

    static final WorldgenLedger LEDGER = new WorldgenLedger();
    private static final Set<String> asked = ConcurrentHashMap.newKeySet();
    private static volatile boolean busy;
    private static volatile boolean off;

    private TileHost() { }

    static boolean enabled() {
        return MeshConfig.DISTRIBUTE_WORLDGEN.get() && !off && LEDGER.distributing();
    }

    /** Called on the server thread every 30 seconds while hosting. */
    static void tick(MinecraftServer server, MeshService s) {
        if (!enabled() || busy) {
            return;
        }
        List<WorldgenPlanner.Player> players = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            var v = p.getDeltaMovement();
            players.add(new WorldgenPlanner.Player(p.level().dimension().location().toString(), p.chunkPosition().x, p.chunkPosition().z, v.x, v.z));
        }
        // research and the end to end run: a player who is not there
        String fake = System.getProperty("mcmesh.worldgen.fakeplayer");
        if (fake != null) {
            String[] xz = fake.split(",");
            players.add(new WorldgenPlanner.Player(WorldgenPlanner.DIM, Integer.parseInt(xz[0].trim()), Integer.parseInt(xz[1].trim()), 0, 0));
        }
        String seed = Long.toString(server.getWorldData().worldGenOptions().seed());
        String profile = WorldgenProfile.of(server);
        busy = true;
        Thread.ofVirtual().name("mesh-tilehost").start(() -> {
            try {
                round(s, players, profile, seed);
            } catch (GatewayException e) {
                if (e.code.equals("WORLDGEN_OFF")) {
                    off = true;
                    LOG.info("distributed worldgen is off for this world on the gateway, not asking again this session");
                } else if (!e.leaseLost()) {
                    LOG.debug("worldgen round: {}", e.toString());
                }
            } catch (Exception e) {
                LOG.warn("worldgen round failed: {}", e.toString());
            } finally {
                busy = false;
            }
        });
    }

    private static void round(MeshService s, List<WorldgenPlanner.Player> players, String profile, String seed) throws Exception {
        GatewayClient gw = s.gateway();
        String world = s.worldId();
        Path root = s.worldRoot();
        // what is still queued counts against the limit, or every round would pile eight more on top
        JsonObject counts = gw.worldgenState(world).getAsJsonObject("tiles");
        int outstanding = counts.has("queued") ? counts.get("queued").getAsInt() : 0;
        var planned = WorldgenPlanner.plan(players, REGIONS_AHEAD, SKIP_NEAR, Math.max(0, PER_ROUND - outstanding), (rx, rz) -> asked.contains(rx + "," + rz) || !TileInstall.requestable(root, rx, rz));
        if (!planned.isEmpty()) {
            List<int[]> tiles = new ArrayList<>();
            for (WorldgenTiles.Tile t : planned) {
                tiles.add(new int[] {t.x(), t.z()});
            }
            int queued = gw.worldgenRequest(world, profile, seed, WorldgenPlanner.DIM, tiles);
            planned.forEach(t -> asked.add((t.x() >> 5) + "," + (t.z() >> 5)));
            LOG.info("worldgen: asked for {} tiles, {} new", tiles.size(), queued);
        }
        JsonObject r = gw.worldgenTiles(world);
        for (JsonElement e : r.getAsJsonArray("refused")) {
            JsonObject t = e.getAsJsonObject();
            LEDGER.record(WorldgenTiles.Verdict.MISMATCH);
            gw.worldgenConsumed(world, t.get("tile").getAsLong());
            LOG.warn("worldgen: peers never agreed on the tile at {},{}, the host generates it itself ({})", t.get("x").getAsInt(), t.get("z").getAsInt(), LEDGER.describe());
        }
        for (JsonElement e : r.getAsJsonArray("ready")) {
            install(gw, world, root, e.getAsJsonObject());
        }
    }

    /** A chunk from a worker is never trusted: it must be the chunk it says it is, from this game version, at a status we know. */
    static String problem(String kind, int cx, int cz, CompoundTag t) {
        if (!t.contains("DataVersion", 3) || t.getInt("DataVersion") != SharedConstants.getCurrentVersion().getDataVersion().getVersion()) {
            return "wrong data version " + (t.contains("DataVersion", 3) ? t.getInt("DataVersion") : "none");
        }
        if (kind.equals("region")) {
            if (t.getInt("xPos") != cx || t.getInt("zPos") != cz) {
                return "claims to be chunk " + t.getInt("xPos") + "," + t.getInt("zPos");
            }
            if (TileMerge.rank(t.getString("Status")) < 0) {
                return "unknown status '" + t.getString("Status") + "'";
            }
        } else if (kind.equals("entities")) {
            int[] p = t.getIntArray("Position");
            if (p.length != 2 || p[0] != cx || p[1] != cz) {
                return "entities of another chunk";
            }
        }
        return null;
    }

    private static void install(GatewayClient gw, String world, Path root, JsonObject t) throws Exception {
        long id = t.get("tile").getAsLong();
        int x = t.get("x").getAsInt();
        int z = t.get("z").getAsInt();
        long t0 = System.nanoTime();
        List<TileInstall.Chunk> chunks = fetch(gw, t);
        TileInstall.Result res = chunks == null ? new TileInstall.Result(false, 0, 0, 0) : TileInstall.install(root, x >> 5, z >> 5, chunks);
        gw.worldgenConsumed(world, id);
        if (chunks == null) {
            LOG.warn("worldgen: the tile at {},{} did not pass the checks and was dropped", x, z);
        } else if (res.installed()) {
            LEDGER.record(WorldgenTiles.Verdict.ACCEPT);
            LOG.info("worldgen: installed the tile at {},{} ({} chunk entries written, {} kept as they were, {} neighbouring regions left alone, {} ms), {}",
                    x, z, res.written(), res.keptExisting(), res.regionsSkipped(), (System.nanoTime() - t0) / 1_000_000, LEDGER.describe());
        } else {
            LOG.info("worldgen: the game opened region {},{} before its tile arrived, the tile is dropped", x >> 5, z >> 5);
        }
    }

    /** Downloads, hash checks and sanity checks every chunk of a ready tile. Null when anything is wrong with the tile itself. */
    private static List<TileInstall.Chunk> fetch(GatewayClient gw, JsonObject t) throws Exception {
        int x = t.get("x").getAsInt();
        int z = t.get("z").getAsInt();
        var leaves = t.getAsJsonArray("leaves");
        ExecutorService pool = Executors.newFixedThreadPool(FETCHERS);
        try {
            List<Future<TileInstall.Chunk>> work = new ArrayList<>();
            for (JsonElement le : leaves) {
                JsonObject l = le.getAsJsonObject();
                String kind = l.get("kind").getAsString();
                int cx = l.get("cx").getAsInt();
                int cz = l.get("cz").getAsInt();
                boolean frontier = l.has("frontier") && l.get("frontier").getAsBoolean();
                byte[] hash = Bytes.unhex(l.get("hash").getAsString());
                work.add(pool.submit(() -> {
                    byte[] data = gw.blobGet(hash);
                    if (!Arrays.equals(Bytes.sha256(data), hash)) {
                        throw new IOException("a chunk of the tile does not match its hash");
                    }
                    CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(data)), NbtAccounter.unlimitedHeap());
                    String why = problem(kind, cx, cz, tag);
                    String status = kind.equals("region") ? tag.getString("Status") : null;
                    if (why != null) {
                        if (!frontier) {
                            throw new IOException("chunk " + kind + " " + cx + "," + cz + " of the tile " + why);
                        }
                        return null;
                    }
                    // the tile itself must be finished terrain; only the frontier may be partly generated
                    if (!frontier && kind.equals("region") && !"minecraft:full".equals(status)) {
                        throw new IOException("chunk " + cx + "," + cz + " of the tile is at status " + status);
                    }
                    return new TileInstall.Chunk(kind, cx, cz, data, status, frontier);
                }));
            }
            List<TileInstall.Chunk> out = new ArrayList<>();
            for (Future<TileInstall.Chunk> f : work) {
                try {
                    TileInstall.Chunk c = f.get();
                    if (c != null) {
                        out.add(c);
                    }
                } catch (java.util.concurrent.ExecutionException e) {
                    LOG.warn("worldgen: tile at {},{}: {}", x, z, e.getCause() == null ? e : e.getCause().getMessage());
                    return null;
                }
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }
}
