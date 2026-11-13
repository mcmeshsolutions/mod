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
    private static final List<String> KINDS = List.of("region", "entities", "poi");

    private WorldSync() { }

    /** Returns true when the revision is empty, meaning the local folder (if any) is the world to seed from. */
    static boolean sync(GatewayClient gw, Path root, String rev) throws Exception {
        Path kept = dev.mcmesh.core.RejectedCopy.setAside(root);
        if (kept != null) {
            LOG.warn("the gateway refused the last save of this world; that copy is kept in {}", kept);
        }
        Path marker = root.resolve(MARKER);
        String have = Files.exists(marker) ? Files.readString(marker).trim() : null;
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
        wipeChunkStores(root);
        Files.createDirectories(root);
        Sink sink = new Sink(root);
        try {
            SyncProgress.begin();
            int n = WorldDownloader.download(gw, rev, sink, SyncProgress::update);
            LOG.info("downloaded {} files for {}", n, rev);
        } finally {
            sink.close();
            SyncProgress.end();
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

    private static final class Sink implements WorldDownloader.Sink {
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
            if (first != null) {
                throw first;
            }
        }
    }
}
