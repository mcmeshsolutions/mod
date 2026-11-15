package dev.mcmesh.neoforge;

import com.mojang.logging.LogUtils;
import dev.mcmesh.core.GatewayClient;
import dev.mcmesh.core.RevisionFiles;
import dev.mcmesh.core.WorldDownloader;
import dev.mcmesh.core.WorldPaths;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.slf4j.Logger;

/** Brings the local world folder to a given revision before the server loads it. */
final class WorldSync {
    private static final Logger LOG = LogUtils.getLogger();
    static final String MARKER = "mcmesh.rev";
    /** A folder that holds only the regions of one shard lease: never mistaken for a whole copy of the world. */
    static final String SHARD_MARKER = "mcmesh.shard";
    private static final List<String> KINDS = List.of("region", "entities", "poi");

    private WorldSync() { }

    /** Returns true when the revision is empty, meaning the local folder (if any) is the world to seed from. */
    static boolean sync(GatewayClient gw, Path root, String rev) throws Exception {
        return sync(gw, root, rev, new boolean[1]);
    }

    /**
     * @param keptLocalTerrain set to true when the revision lists no chunks at all but the local folder has some: the gateway
     *     never received this world's terrain, so the local chunks are the only copy. They are kept, not wiped, and the caller
     *     should upload them in full on the next save.
     */
    static boolean sync(GatewayClient gw, Path root, String rev, boolean[] keptLocalTerrain) throws Exception {
        String why = dev.mcmesh.core.RejectedCopy.reason(root);
        Path kept = dev.mcmesh.core.RejectedCopy.setAside(root);
        if (kept != null) {
            LOG.warn("unclean-stop".equals(why) ? "the last hosting run did not stop cleanly; the local copy is kept in {}"
                    : "the gateway refused the last save of this world; that copy is kept in {}", kept);
        }
        Path marker = root.resolve(MARKER);
        String have = Files.exists(marker) ? Files.readString(marker).trim() : null;
        if (have != null && !LazyWorld.savedPending(root).isEmpty()) {
            String savedRev = LazyWorld.savedRev(root);
            LazyWorld.completeFromState(gw, root, gw.listRevision(savedRev != null ? savedRev : have));
        }
        if (rev.equals(have)) {
            return false;
        }
        RevisionFiles files = gw.listRevision(rev);
        if (files.chunks().isEmpty() && files.aux().isEmpty()) {
            LOG.info("revision {} is empty, keeping the local world", rev);
            return true;
        }
        if (have == null && hasChunkData(root)) {
            throw new IOException("refusing to overwrite an unmanaged world in " + root + ", move it away first");
        }
        boolean terrainOnlyHere = files.chunks().isEmpty() && hasChunkData(root);
        if (terrainOnlyHere) {
            keptLocalTerrain[0] = true;
            LOG.warn("the gateway has no terrain for this world; keeping the local chunks and uploading them on the next save");
        }
        boolean incremental = false;
        if (have != null && hasChunkData(root) && dev.mcmesh.core.LocalWorldCheck.untouchedSince(root, marker)) {
            incremental = tryIncremental(gw, root, have, rev);
        }
        if (!incremental) {
            LazyWorld.clearState(root);
            if (!terrainOnlyHere) {
                wipeChunkStores(root);
            }
            Files.createDirectories(root);
            Sink sink = new Sink(root);
            try {
                SyncProgress.begin();
                if (MeshConfig.LAZY_BOOTSTRAP.get() && MeshService.safeMode().lazyBootstrap()
                        && files.chunks().size() >= MeshConfig.LAZY_MIN_CHUNKS.get()) {
                    lazyDownload(gw, root, rev, files, sink, marker);
                } else {
                    int n = WorldDownloader.download(gw, rev, sink, SyncProgress::update);
                    LOG.info("downloaded {} files for {}", n, rev);
                }
            } finally {
                sink.close();
                SyncProgress.end();
            }
        }
        java.util.Set<String> keep = new java.util.HashSet<>();
        files.aux().forEach(a -> keep.add(a.path()));
        for (Path f : AuxSync.candidates(root)) {
            if (!keep.contains(root.relativize(f).toString().replace('\\', '/'))) {
                Files.delete(f);
            }
        }
        Files.writeString(marker, rev, StandardCharsets.UTF_8);
        return false;
    }

    /** Extra files first (the anchors come from level.dat and playerdata), then the regions near them; the rest is handed to LazyWorld. */
    private static void lazyDownload(GatewayClient gw, Path root, String rev, RevisionFiles files, Sink sink, Path marker) throws Exception {
        WorldDownloader.downloadParts(gw, List.of(), files.aux(), sink, SyncProgress::update);
        var anchors = LazyWorld.anchors(root);
        var eager = dev.mcmesh.core.LazyPlan.eagerRegions(files.chunks(), anchors, MeshConfig.LAZY_RADIUS_REGIONS.get(), MeshConfig.LAZY_SMALL_DIM_CHUNKS.get());
        var split = dev.mcmesh.core.LazyPlan.split(files.chunks(), eager);
        int n = WorldDownloader.downloadParts(gw, split.eager(), List.of(), sink, SyncProgress::update);
        LOG.info("lazy bootstrap: {} anchors, {} chunks downloaded now, {} regions left for later", anchors.size(), n, split.pending().size());
        sink.close();
        if (split.pending().isEmpty()) {
            return;
        }
        LazyWorld w = LazyWorld.begin(gw, root, split.pending(), (int) (System.currentTimeMillis() / 1000) - 3600);
        // the marker must exist before the world opens, or a restart would call this folder unmanaged
        Files.writeString(marker, rev, StandardCharsets.UTF_8);
        w.startDrain();
    }

    /**
     * The local copy equals revision `have`, so only what changed since then needs to come down. Anything unexpected
     * (a pruned old revision, a network error halfway) returns false and the caller does the normal full download,
     * which wipes the chunk stores first, so a half applied delta never survives.
     */
    private static boolean tryIncremental(GatewayClient gw, Path root, String have, String rev) {
        Sink sink = new Sink(root);
        try {
            SyncProgress.begin();
            var planned = WorldDownloader.plan(gw.listRevision(have), gw.listRevision(rev));
            if (MeshConfig.LAZY_BOOTSTRAP.get() && MeshService.safeMode().lazyBootstrap() && planned.chunks().size() >= Integer.getInteger("mcmesh.lazy.deltaMin", LAZY_DELTA_MIN)) {
                lazyDelta(gw, root, rev, planned, sink);
                return true;
            }
            var d = WorldDownloader.downloadDelta(gw, have, rev, sink, SyncProgress::update);
            LOG.info("incremental sync {} to {}: {} chunks and {} extra files fetched, {} chunks removed", have, rev, d.chunks().size(), d.aux().size(), d.removedChunks().size());
            return true;
        } catch (Exception e) {
            LOG.warn("incremental sync from {} failed, downloading the whole world: {}", have, e.toString());
            return false;
        } finally {
            try {
                sink.close();
            } catch (IOException e) {
                LOG.warn("closing region files after the incremental sync failed: {}", e.toString());
            }
            SyncProgress.end();
        }
    }

    /**
     * A shard host's world: wiped, then the extra files of the revision and only the chunks of its regions, with a spawn point of
     * its own inside them so the game does not generate the spawn area of a world it owns none of. The folder is the shard's alone:
     * it is refused when it holds an ordinary copy of the world, and the level.dat edit is local, a shard never uploads level.dat.
     */
    static void syncShard(GatewayClient gw, Path root, String rev, List<dev.mcmesh.core.ShardRegion> regions) throws Exception {
        Path marker = root.resolve(SHARD_MARKER);
        if (Files.exists(root) && !Files.exists(marker) && (Files.exists(root.resolve(MARKER)) || hasChunkData(root))) {
            throw new IOException("refusing to turn " + root + " into a shard copy: it holds a normal world. Give the shard host its own level-name");
        }
        deleteTree(root);
        Files.createDirectories(root);
        RevisionFiles files = gw.listRevision(rev);
        List<RevisionFiles.Chunk> mine = new java.util.ArrayList<>();
        for (RevisionFiles.Chunk c : files.chunks()) {
            if (dev.mcmesh.core.ShardRegion.any(regions, c.dim(), c.cx() >> 5, c.cz() >> 5)) {
                mine.add(c);
            }
        }
        Sink sink = new Sink(root);
        try {
            SyncProgress.begin();
            int n = WorldDownloader.downloadParts(gw, mine, files.aux(), sink, SyncProgress::update);
            LOG.info("shard copy of {}: {} files, {} of the {} chunks of the world", rev, n, mine.size(), files.chunks().size());
        } finally {
            sink.close();
            SyncProgress.end();
        }
        localizeLevel(root, regions.get(0));
        Files.writeString(marker, rev, StandardCharsets.UTF_8);
    }

    private static void localizeLevel(Path root, dev.mcmesh.core.ShardRegion first) throws IOException {
        Path level = root.resolve("level.dat");
        if (!Files.isRegularFile(level)) {
            return;
        }
        var tag = net.minecraft.nbt.NbtIo.readCompressed(level, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        var data = tag.getCompound("Data");
        data.putInt("SpawnX", first.centerBlockX());
        data.putInt("SpawnZ", first.centerBlockZ());
        // no spawn chunks: they would keep a region loaded that this host does not own
        var rules = data.contains("GameRules", 10) ? data.getCompound("GameRules") : new net.minecraft.nbt.CompoundTag();
        rules.putString("spawnChunkRadius", "0");
        data.put("GameRules", rules);
        net.minecraft.nbt.NbtIo.writeCompressed(tag, level);
    }

    /** A delta smaller than this is fetched at once; the bookkeeping of a lazy start is not worth it. */
    private static final int LAZY_DELTA_MIN = 512;

    /**
     * The same idea as {@link #lazyDownload} for a copy that is only behind: the extra files and the changed chunks near spawn and the
     * players come down now, the changed chunks of every other region wait and are patched into the files already on disk when the
     * game first opens the region, or by the drain, nearest the players first. Removed chunks are dropped at once.
     */
    private static void lazyDelta(GatewayClient gw, Path root, String rev, WorldDownloader.Delta d, Sink sink) throws Exception {
        for (var c : d.removedChunks()) {
            sink.removeChunk(c);
        }
        WorldDownloader.downloadParts(gw, List.of(), d.aux(), sink, SyncProgress::update);
        var anchors = LazyWorld.anchors(root);
        var eager = dev.mcmesh.core.LazyPlan.eagerRegions(d.chunks(), anchors, MeshConfig.LAZY_RADIUS_REGIONS.get(), MeshConfig.LAZY_SMALL_DIM_CHUNKS.get());
        var split = dev.mcmesh.core.LazyPlan.split(d.chunks(), eager);
        int n = WorldDownloader.downloadParts(gw, split.eager(), List.of(), sink, SyncProgress::update);
        LOG.info("lazy delta: {} anchors, {} changed chunks downloaded now, {} regions with {} changed chunks left for later, {} chunks removed",
                anchors.size(), n, split.pending().size(), split.pendingChunks(), d.removedChunks().size());
        sink.close();
        if (split.pending().isEmpty()) {
            return;
        }
        LazyWorld w = LazyWorld.begin(gw, root, split.pending(), (int) (System.currentTimeMillis() / 1000) - 3600, true);
        // the marker must name the new revision before the world opens, or a restart would call this folder unmanaged
        Files.writeString(root.resolve(MARKER), rev, StandardCharsets.UTF_8);
        w.startDrain();
    }

    static boolean hasChunkData(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> s = Files.walk(root)) {
            return s.anyMatch(f -> f.getFileName().toString().endsWith(".mca"));
        }
    }

    private static void wipeChunkStores(Path root) throws IOException {
        List<Path> dims = WorldPaths.dimDirs(root);
        for (Path d : dims) {
            for (String k : KINDS) {
                deleteTree(d.resolve(k));
            }
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path f : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.delete(f);
            }
        }
    }

    static final class Sink implements WorldDownloader.Sink {
        private final Path root;
        private final Map<String, RegionFile> files = new HashMap<>();

        Sink(Path root) {
            this.root = root;
        }

        @Override
        public synchronized void chunk(RevisionFiles.Chunk c, byte[] data) throws IOException {
            if (!KINDS.contains(c.kind())) {
                throw new IOException("unknown chunk kind " + c.kind());
            }
            int rx = c.cx() >> 5;
            int rz = c.cz() >> 5;
            String id = c.dim() + "|" + c.kind() + "|" + rx + "," + rz;
            RegionFile rf = files.get(id);
            if (rf == null) {
                Path folder = WorldPaths.dirOf(root, c.dim()).resolve(c.kind());
                Files.createDirectories(folder);
                ResourceKey<net.minecraft.world.level.Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(c.dim()));
                RegionStorageInfo info = new RegionStorageInfo(root.getFileName().toString(), key, c.kind());
                rf = new RegionFile(info, folder.resolve("r." + rx + "." + rz + ".mca"), folder, false);
                files.put(id, rf);
            }
            try (DataOutputStream out = rf.getChunkDataOutputStream(new ChunkPos(c.cx(), c.cz()))) {
                out.write(data);
            }
        }

        @Override
        public synchronized void removeChunk(RevisionFiles.Chunk c) throws IOException {
            if (!KINDS.contains(c.kind())) {
                return;
            }
            int rx = c.cx() >> 5;
            int rz = c.cz() >> 5;
            Path folder = WorldPaths.dirOf(root, c.dim()).resolve(c.kind());
            if (!Files.exists(folder.resolve("r." + rx + "." + rz + ".mca"))) {
                return;
            }
            String id = c.dim() + "|" + c.kind() + "|" + rx + "," + rz;
            RegionFile rf = files.get(id);
            if (rf == null) {
                ResourceKey<net.minecraft.world.level.Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(c.dim()));
                rf = new RegionFile(new RegionStorageInfo(root.getFileName().toString(), key, c.kind()), folder.resolve("r." + rx + "." + rz + ".mca"), folder, false);
                files.put(id, rf);
            }
            rf.clear(new ChunkPos(c.cx(), c.cz()));
        }

        @Override
        public void aux(RevisionFiles.Aux a, byte[] data) throws IOException {
            Path dest = WorldPaths.auxPath(root, a.path());
            Files.createDirectories(dest.getParent());
            Path tmp = dest.resolveSibling(dest.getFileName() + ".mesh-tmp");
            Files.write(tmp, data);
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }

        synchronized void close() throws IOException {
            IOException first = null;
            for (RegionFile s : files.values()) {
                try {
                    s.close();
                } catch (IOException e) {
                    first = first == null ? e : first;
                }
            }
            files.clear();
            if (first != null) {
                throw first;
            }
        }
    }
}
