package dev.mcmesh.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcmesh.core.Bytes;
import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.core.LazyPlan;
import dev.mcmesh.core.McaTimestamps;
import dev.mcmesh.core.RevisionFiles;
import dev.mcmesh.core.WorldPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Regions of the world that this host has not downloaded yet. The world opens with only the regions near spawn and the players on
 * disk; every other region is fetched whole, all three kinds (region, entities, poi) together, either when the game is about to open
 * it (the guard) or by the background drain.
 *
 * Why it is safe: the game never sees a pending region as absent. The mixin calls {@link #guard} before any region file is opened,
 * and a failed fetch is retried until it works instead of failing the read (a failed read makes the game generate new terrain over
 * the build). A region leaves the pending list only after its files are complete on disk, and the list is saved before the game
 * gets the region. The list lives in the config folder, never in the world, so it is never uploaded. A restart finishes the rest
 * before the world loads.
 */
final class LazyWorld {
    private static final Logger LOG = LoggerFactory.getLogger("mcmesh");
    private static final List<String> KINDS = List.of("region", "entities", "poi");

    static volatile LazyWorld active;

    private final GatewayClient gw;
    private final Path root;
    private final Path stateFile;
    private final int backdateSec;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, List<RevisionFiles.Chunk>> pending;
    private final java.util.concurrent.ConcurrentLinkedDeque<String> priority = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private volatile boolean closed;
    private int done;
    private final int total;

    private LazyWorld(GatewayClient gw, Path root, Map<String, List<RevisionFiles.Chunk>> pending, int backdateSec) {
        this.gw = gw;
        this.root = root;
        this.stateFile = stateFile(root);
        this.pending = new java.util.concurrent.ConcurrentHashMap<>(pending);
        this.total = pending.size();
        this.backdateSec = backdateSec;
    }

    static Path stateFile(Path root) {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("mcmesh-lazy-" + root.getFileName() + ".json");
    }

    /** Regions the last session left pending (empty when there is no state file). */
    static List<String> savedPending(Path root) {
        Path f = stateFile(root);
        try {
            if (!Files.isRegularFile(f)) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (var e : JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("pending")) {
                out.add(e.getAsString());
            }
            return out;
        } catch (Exception e) {
            LOG.warn("unreadable lazy state {}: {}", f, e.toString());
            return List.of();
        }
    }

    static void clearState(Path root) throws IOException {
        Files.deleteIfExists(stateFile(root));
    }

    /** Anchors: the overworld spawn from level.dat and the last position of every player in playerdata. Best effort. */
    static List<LazyPlan.Anchor> anchors(Path root) {
        List<LazyPlan.Anchor> out = new ArrayList<>();
        try {
            Path level = root.resolve("level.dat");
            if (Files.isRegularFile(level)) {
                var data = net.minecraft.nbt.NbtIo.readCompressed(level, net.minecraft.nbt.NbtAccounter.unlimitedHeap()).getCompound("Data");
                out.add(new LazyPlan.Anchor("minecraft:overworld", data.getInt("SpawnX") >> 4, data.getInt("SpawnZ") >> 4));
            }
        } catch (Exception e) {
            LOG.debug("no spawn anchor: {}", e.toString());
        }
        Path players = root.resolve("playerdata");
        if (Files.isDirectory(players)) {
            try (var files = Files.list(players)) {
                for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".dat")).toList()) {
                    try {
                        var t = net.minecraft.nbt.NbtIo.readCompressed(f, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                        var pos = t.getList("Pos", 6);
                        if (pos.size() == 3) {
                            String dim = t.contains("Dimension", 8) ? t.getString("Dimension") : "minecraft:overworld";
                            out.add(new LazyPlan.Anchor(dim, (int) Math.floor(pos.getDouble(0)) >> 4, (int) Math.floor(pos.getDouble(2)) >> 4));
                        }
                    } catch (Exception e) {
                        LOG.debug("no anchor from {}: {}", f.getFileName(), e.toString());
                    }
                }
            } catch (IOException e) {
                LOG.debug("could not list playerdata: {}", e.toString());
            }
        }
        return out;
    }

    /** Starts managing the given pending regions. The caller has already written the eager ones. */
    static LazyWorld begin(GatewayClient gw, Path root, Map<String, List<RevisionFiles.Chunk>> pending, int backdateSec) throws IOException {
        LazyWorld w = new LazyWorld(gw, root, pending, backdateSec);
        w.persist();
        active = w;
        LOG.info("lazy bootstrap: {} regions ({} chunks) wait on the gateway, the rest is on disk", pending.size(), pending.values().stream().mapToInt(List::size).sum());
        return w;
    }

    private void persist() throws IOException {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        pending.keySet().forEach(a::add);
        o.add("pending", a);
        Files.createDirectories(stateFile.getParent());
        Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Regions to fetch before the background order: where players are and where they are heading. */
    void prefetch(java.util.List<String> keys) {
        for (String k : keys) {
            if (pending.containsKey(k) && !priority.contains(k)) {
                priority.addLast(k);
            }
        }
    }

    /** Region keys still waiting on the gateway, for the island probe and the spatial debug view. */
    java.util.Set<String> pendingKeys() {
        return java.util.Set.copyOf(pending.keySet());
    }

    /** True while a lazy bootstrap still owes this region to the gateway; it overwrites whatever is on disk when it arrives. */
    static boolean owes(String dim, int rx, int rz) {
        LazyWorld w = active;
        return w != null && w.pending.containsKey(LazyPlan.regionKey(dim, rx << 5, rz << 5));
    }

    /** Called by the region storage before it opens any region file. Blocks until the region is on disk. */
    void guard(String dim, int cx, int cz) {
        String key = LazyPlan.regionKey(dim, cx, cz);
        if (!pending.containsKey(key)) {
            return;
        }
        LOG.info("lazy bootstrap: the game needs region {} now, fetching it first", key);
        ensure(key);
    }

    void ensure(String key) {
        lock.lock();
        try {
            List<RevisionFiles.Chunk> chunks = pending.get(key);
            if (chunks == null) {
                return;
            }
            materialize(key, chunks);
            pending.remove(key);
            persist();
            done++;
            if (done % 25 == 0 || pending.isEmpty()) {
                LOG.info("lazy bootstrap: {} of {} regions on disk", done, total);
            }
        } catch (IOException e) {
            // never let the game read a pending region as absent: it would generate terrain over the build
            LOG.error("lazy bootstrap: could not complete region {}: {}", key, e.toString());
            throw new java.io.UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    private void materialize(String key, List<RevisionFiles.Chunk> chunks) throws IOException {
        // a partial file from a crash is ours to replace: the game has never opened a pending region
        String[] k = key.split("\\|");
        for (String kind : KINDS) {
            Files.deleteIfExists(WorldPaths.dirOf(root, k[0]).resolve(kind).resolve("r." + k[1] + "." + k[2] + ".mca"));
        }
        WorldSync.Sink sink = new WorldSync.Sink(root);
        try {
            for (RevisionFiles.Chunk c : chunks) {
                sink.chunk(c, fetchForever(c.hash()));
            }
        } finally {
            sink.close();
        }
        for (String kind : KINDS) {
            McaTimestamps.backdate(WorldPaths.dirOf(root, k[0]).resolve(kind).resolve("r." + k[1] + "." + k[2] + ".mca"), backdateSec);
        }
    }

    private byte[] fetchForever(byte[] hash) throws IOException {
        long backoff = 500;
        while (true) {
            try {
                byte[] data = gw.blobGet(hash);
                if (Arrays.equals(Bytes.sha256(data), hash)) {
                    return data;
                }
                LOG.warn("lazy bootstrap: blob {} failed its hash check, asking again", Bytes.hex(hash));
            } catch (Exception e) {
                LOG.warn("lazy bootstrap: fetch failed ({}), retrying", e.toString());
            }
            if (closed) {
                throw new IOException("lazy bootstrap closed while a region was still being fetched");
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", ie);
            }
            backoff = Math.min(backoff * 2, 10_000);
        }
    }

    /** Fetches the remaining regions in the background, one at a time, yielding to the game's own requests. */
    void startDrain() {
        Thread.ofVirtual().name("mesh-lazy-drain").start(() -> {
            while (!closed) {
                String next;
                lock.lock();
                try {
                    String hot;
                    while ((hot = priority.pollFirst()) != null && !pending.containsKey(hot)) {
                        // already fetched by the game
                    }
                    next = hot != null ? hot : pending.isEmpty() ? null : pending.keySet().iterator().next();
                } finally {
                    lock.unlock();
                }
                if (next == null) {
                    try {
                        clearState(root);
                    } catch (IOException e) {
                        LOG.warn("could not remove the lazy state: {}", e.toString());
                    }
                    active = null;
                    LOG.info("lazy bootstrap complete: all {} regions are on disk", total);
                    return;
                }
                try {
                    ensure(next);
                    Thread.sleep(Long.getLong("mcmesh.lazy.drainDelayMs", 20L));
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    return;
                }
            }
        });
    }

    /** Stops the drain; what is still pending stays in the state file for the next start. */
    void close() {
        closed = true;
        if (active == this) {
            active = null;
        }
    }

    /** Fetches everything still listed in the state file, from the given listing, before the world loads. */
    static void completeFromState(GatewayClient gw, Path root, RevisionFiles files) throws IOException {
        List<String> saved = savedPending(root);
        if (saved.isEmpty()) {
            clearState(root);
            return;
        }
        Map<String, List<RevisionFiles.Chunk>> byKey = new LinkedHashMap<>();
        for (String s : saved) {
            byKey.put(s, new ArrayList<>());
        }
        for (RevisionFiles.Chunk c : files.chunks()) {
            List<RevisionFiles.Chunk> l = byKey.get(LazyPlan.regionKey(c.dim(), c.cx(), c.cz()));
            if (l != null) {
                l.add(c);
            }
        }
        LazyWorld w = new LazyWorld(gw, root, byKey, (int) (System.currentTimeMillis() / 1000) - 3600);
        LOG.info("lazy bootstrap: finishing {} regions left by the last session before the world loads", saved.size());
        for (String key : saved) {
            w.ensure(key);
        }
        clearState(root);
    }
}
