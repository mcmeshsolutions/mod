package dev.mcmesh.neoforge;

import dev.mcmesh.core.ChunkUploader;
import dev.mcmesh.core.McaReader;
import dev.mcmesh.core.WorldPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the chunks written to the region files since the last scan and hands them to the uploader. This does not depend on how
 * the server writes chunks: mods that replace Minecraft's chunk storage (C2ME does) bypass the write hook, but every writer ends
 * up putting the chunk and its timestamp into the same region file, which is what this reads.
 */
final class RegionScan {
    private static final Logger LOG = LoggerFactory.getLogger("mcmesh");
    private static final List<String> KINDS = List.of("region", "entities", "poi");
    private static final McaReader.Decompressor OTHER = (type, raw) -> {
        var v = net.minecraft.world.level.chunk.storage.RegionFileVersion.fromId(type);
        return v == null ? null : v.wrap(raw);
    };

    /** Chunks stamped at or after this second (minus slack) count as written since the last scan. */
    private long sinceSec;

    /** @param baselineMs the moment the local world last equalled the gateway's revision, i.e. right after the sync */
    RegionScan(long baselineMs) {
        sinceSec = baselineMs / 1000 - 1;
    }

    /** A region file named r.X.Z.mca belongs to this process unless it lies in someone else's part of the world. */
    private static boolean ownsFile(String dim, Path f) {
        String[] n = f.getFileName().toString().split("\\.");
        try {
            return Ownership.writes(dim, Integer.parseInt(n[1]) << 5, Integer.parseInt(n[2]) << 5);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Returns how many chunks were handed to the uploader. Call after the server has flushed its chunks to disk. */
    int scan(Path root, ChunkUploader up) throws IOException {
        long started = System.currentTimeMillis() / 1000;
        long since = sinceSec;
        int sent = 0;
        boolean complete = true;
        for (Path dimDir : WorldPaths.dimDirs(root)) {
            String dim = WorldPaths.dimOf(root, dimDir);
            for (String kind : KINDS) {
                Path folder = dimDir.resolve(kind);
                if (!Files.isDirectory(folder)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(folder)) {
                    for (Path f : (Iterable<Path>) files.filter(p -> p.getFileName().toString().endsWith(".mca"))::iterator) {
                        if (!ownsFile(dim, f)) {
                            continue;
                        }
                        // a file nobody touched since the last scan has nothing new, and checking is one stat call
                        if (Files.getLastModifiedTime(f).toMillis() / 1000 < since - 2) {
                            continue;
                        }
                        for (McaReader.Changed c : McaReader.changedSince(f, since)) {
                            byte[] nbt = McaReader.readChunk(f, c.cx(), c.cz(), OTHER);
                            if (nbt == null) {
                                complete = false;
                                continue;
                            }
                            up.put(dim, kind, c.cx(), c.cz(), nbt);
                            sent++;
                        }
                    }
                }
            }
        }
        // a chunk that could not be read yet (mid write) is looked at again next time; duplicates are removed by hash upstream
        if (complete) {
            sinceSec = started - 1;
        }
        if (sent > 0) {
            LOG.info("region scan: {} changed chunks handed to the uploader", sent);
        }
        return sent;
    }
}
