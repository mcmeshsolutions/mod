package dev.mcmesh.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcmesh.core.Bytes;
import dev.mcmesh.core.GatewayClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A dedicated server that does nothing but generate one tile for a host and exit (-Dmcmesh.worker=true). One tile per
 * process on purpose: a world that already holds one tile and its frontier generates the next tile next to it
 * differently from a fresh world, and two workers only agree when both started from nothing. A wrapper wipes the
 * world folder and starts it again (deploy/worldgen-worker.sh).
 */
final class TileWorker {
    private static final Logger LOG = LoggerFactory.getLogger("mcmesh");
    private static final long IDLE_MS = 20_000;
    private static final int UPLOADERS = 6;
    private static final String[] KINDS = {"region", "entities", "poi"};
    /** The frontier reaches past the finished halo by this many chunks, to include the partially generated rings the seam fix needs. */
    private static final int PAST_HALO = 8;

    private TileWorker() { }

    static boolean enabled() {
        return Boolean.getBoolean("mcmesh.worker");
    }

    static void start(MinecraftServer server) {
        Thread t = new Thread(() -> run(server), "mesh-tile-worker");
        t.setDaemon(true);
        t.start();
    }

    private static void run(MinecraftServer server) {
        try {
            String token = MeshConfig.TOKEN.get();
            if (token.isEmpty()) {
                token = MeshAuth.login().token();
            }
            GatewayClient gw = new GatewayClient(MeshConfig.API_URL.get(), token);
            String profile = WorldgenProfile.of(server);
            LOG.info("tile worker up, profile {} from {}", profile, WorldgenProfile.describe());
            int idle = 0;
            int idleLimit = Integer.getInteger("mcmesh.worker.idleRounds", Integer.MAX_VALUE);
            while (server.isRunning()) {
                GatewayClient.WorldgenClaim c = gw.worldgenClaim(profile);
                if (c == null) {
                    if (++idle > idleLimit) {
                        break;
                    }
                    Thread.sleep(IDLE_MS);
                    continue;
                }
                work(server, gw, c);
                break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.error("tile worker stopped: {}", e.toString());
        } finally {
            server.halt(false);
        }
    }

    private static void work(MinecraftServer server, GatewayClient gw, GatewayClient.WorldgenClaim c) throws Exception {
        if (c.genVersion() != WorldgenProfile.PROCEDURE) {
            LOG.error("the gateway wants generation procedure {} and this build implements {}, not taking tile {}", c.genVersion(), WorldgenProfile.PROCEDURE, c.tile());
            return;
        }
        ServerLevel level = server.overworld();
        int x1 = c.x() - c.halo();
        int z1 = c.z() - c.halo();
        int x2 = c.x() + c.size() + c.halo() - 1;
        int z2 = c.z() + c.size() + c.halo() - 1;
        LOG.info("tile {} ({}, {}) as {}: generating chunks {},{} to {},{}", c.tile(), c.x(), c.z(), c.role(), x1, z1, x2, z2);
        TileGen.Result r = TileGen.generate(level, x1, z1, x2, z2, n -> { });
        if (r.ok() != r.total()) {
            LOG.error("tile {}: only {} of {} chunks reached full status, nothing is reported", c.tile(), r.ok(), r.total());
            return;
        }
        LOG.info("tile {}: {} chunks generated in {} ms, unloading and saving", c.tile(), r.total(), r.millis());
        // a save only writes the finished chunks. The partially generated ones around them (the frontier, which carries the spill of the
        // tile's edge features) are written when the game unloads them, so let go of the area and wait for that first
        TileGen.release(level, x1, z1, x2, z2);
        waitForUnload(server, level);
        server.submit(() -> server.saveEverything(true, true, true)).join();

        boolean generator = c.role().equals("generate");
        List<byte[]> digests = new ArrayList<>();
        JsonArray leaves = new JsonArray();
        List<byte[]> blobs = new ArrayList<>();
        try (RegionIo io = new RegionIo(server.getWorldPath(LevelResource.ROOT))) {
            // the tile first, in the order every worker uses, then (generator only) the frontier
            for (int cx = c.x(); cx < c.x() + c.size(); cx++) {
                for (int cz = c.z(); cz < c.z() + c.size(); cz++) {
                    CompoundTag region = io.read("region", cx, cz);
                    if (region == null || !"minecraft:full".equals(region.getString("Status"))) {
                        LOG.error("tile {}: chunk {},{} was not stored at full status, nothing is reported", c.tile(), cx, cz);
                        return;
                    }
                    CompoundTag entities = io.read("entities", cx, cz);
                    CompoundTag poi = io.read("poi", cx, cz);
                    digests.add(CanonicalChunk.digest(region, entities, poi));
                    if (generator) {
                        CompoundTag[] tags = {region, entities, poi};
                        for (int k = 0; k < KINDS.length; k++) {
                            if (tags[k] != null) {
                                byte[] bytes = RegionIo.bytes(tags[k]);
                                blobs.add(bytes);
                                JsonObject leaf = new JsonObject();
                                leaf.addProperty("kind", KINDS[k]);
                                leaf.addProperty("cx", cx);
                                leaf.addProperty("cz", cz);
                                leaf.addProperty("hash", Bytes.hex(Bytes.sha256(bytes)));
                                leaves.add(leaf);
                            }
                        }
                    }
                }
            }
            if (generator) {
                // everything the world saved just outside the tile, at whatever status it reached: the spill of the tile's edge features
                // lives in these chunks, and without them the first chunks a host generates next to the tile are missing it (RESEARCH.md 1e)
                int reach = c.halo() + PAST_HALO;
                for (int cx = c.x() - reach; cx < c.x() + c.size() + reach; cx++) {
                    for (int cz = c.z() - reach; cz < c.z() + c.size() + reach; cz++) {
                        boolean inTile = cx >= c.x() && cx < c.x() + c.size() && cz >= c.z() && cz < c.z() + c.size();
                        CompoundTag region = inTile ? null : io.read("region", cx, cz);
                        if (region == null) {
                            continue;
                        }
                        CompoundTag[] tags = {region, io.read("entities", cx, cz), io.read("poi", cx, cz)};
                        for (int k = 0; k < KINDS.length; k++) {
                            if (tags[k] != null) {
                                byte[] bytes = RegionIo.bytes(tags[k]);
                                blobs.add(bytes);
                                JsonObject leaf = new JsonObject();
                                leaf.addProperty("kind", KINDS[k]);
                                leaf.addProperty("cx", cx);
                                leaf.addProperty("cz", cz);
                                leaf.addProperty("hash", Bytes.hex(Bytes.sha256(bytes)));
                                leaf.addProperty("frontier", true);
                                leaves.add(leaf);
                            }
                        }
                    }
                }
            }
        }
        if (generator) {
            long frontier = 0;
            for (var le : leaves) {
                if (le.getAsJsonObject().has("frontier") && le.getAsJsonObject().get("kind").getAsString().equals("region")) {
                    frontier++;
                }
            }
            LOG.info("tile {}: {} frontier chunks of terrain will be shipped with the tile", c.tile(), frontier);
            if (frontier < 2000) {
                LOG.warn("tile {}: that is far fewer than expected, so the neighbouring terrain will show a seam", c.tile());
            }
        }
        if (generator) {
            upload(gw, blobs);
        }
        StringBuilder per = new StringBuilder();
        digests.forEach(d -> per.append(CanonicalChunk.hex(d)));
        String outcome = gw.worldgenResult(c.tile(), CanonicalChunk.tile(digests), per.toString(), generator ? leaves : null);
        LOG.info("tile {}: reported {} chunks as {}, the gateway says {}", c.tile(), digests.size(), c.role(), outcome);
    }

    /** Until the loaded chunk count stops falling (the spawn area never leaves), at most two minutes. */
    private static void waitForUnload(MinecraftServer server, ServerLevel level) throws InterruptedException {
        int last = Integer.MAX_VALUE;
        long stable = 0;
        for (int i = 0; i < 240; i++) {
            TileGen.pump(level, 4);
            int now = server.submit(() -> level.getChunkSource().getLoadedChunksCount()).join();
            if (now >= last) {
                if (++stable >= 6) {
                    LOG.info("{} chunks stay loaded, the rest was unloaded and saved", now);
                    return;
                }
            } else {
                stable = 0;
            }
            last = now;
            Thread.sleep(500);
        }
        LOG.warn("the chunks did not finish unloading in two minutes, saving what there is");
    }

    /** Pipelines every tile blob over the persistent blob-stream connection at once, same reasoning as
     *  ChunkUploader.putBlobs: one already-open connection with no concurrency cap of its own saturates
     *  the link instead of being bound by a thread-count guess. Anything with no stream to use, or whose
     *  pipelined upload failed, falls back to UPLOADERS raw HTTP PUTs. */
    private static void upload(GatewayClient gw, List<byte[]> blobs) throws Exception {
        List<byte[]> fallback = new ArrayList<>();
        List<byte[]> pipelined = new ArrayList<>();
        List<java.util.concurrent.CompletableFuture<byte[]>> futures = new ArrayList<>();
        for (byte[] b : blobs) {
            var f = gw.blobPutAsync(b);
            if (f == null) {
                fallback.add(b);
            } else {
                pipelined.add(b);
                // clock starts now, at creation, so a dead stream with many items still pending times
                // all of them out concurrently in ~30s total instead of 30s each, one at a time, in the
                // loop below - see WorldDownloader.fetchAll for the same fix and why it matters.
                futures.add(f.orTimeout(30, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
        for (int i = 0; i < pipelined.size(); i++) {
            try {
                futures.get(i).get();
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof dev.mcmesh.core.GatewayException g) {
                    throw g;
                }
                fallback.add(pipelined.get(i));
            }
        }
        if (fallback.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(UPLOADERS);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (byte[] b : fallback) {
                done.add(pool.submit(() -> {
                    gw.blobPut(b);
                    return null;
                }));
            }
            for (Future<?> f : done) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        LOG.info("uploaded {} blobs", blobs.size());
    }
}
