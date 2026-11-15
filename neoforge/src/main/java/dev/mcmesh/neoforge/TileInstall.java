package dev.mcmesh.neoforge;

import dev.mcmesh.core.TileMerge;
import dev.mcmesh.core.TileMerge.Region;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts a worker's chunks into the host world without ever making it worse (rules in {@link TileMerge}, reasons in
 * board/RESEARCH.md 1e). The game creates a region file the first time anything reads or writes it, and every access
 * passes {@link #touch} first, which records the region as opened before the game is let in. So a region that was never
 * touched and is either absent or was created by an earlier install is safe to write to, and anything else is left alone.
 * The lock makes "check, then write" atomic against the game's first access.
 */
final class TileInstall {
    private static final Logger LOG = LoggerFactory.getLogger("mcmesh");
    private static final String OVERWORLD = "minecraft:overworld";
    private static final List<String> KINDS = List.of("region", "entities", "poi");
    private static final ReentrantLock GATE = new ReentrantLock();
    private static final Set<String> touched = ConcurrentHashMap.newKeySet();

    private TileInstall() { }

    /** One stored chunk to install. `status` is only set for the terrain kind. */
    record Chunk(String kind, int cx, int cz, byte[] nbt, String status, boolean frontier) { }

    record Result(boolean installed, int written, int keptExisting, int regionsSkipped) { }

    private static String key(int rx, int rz) {
        return rx + "." + rz;
    }

    private static Path state(Path root, String what) {
        return root.resolve("mcmesh").resolve("tiles").resolve(what);
    }

    /** The region guard calls this for every overworld region access, before the game's own code runs. */
    static void touch(int rx, int rz) {
        String k = key(rx, rz);
        if (touched.contains(k)) {
            return;
        }
        GATE.lock();
        try {
            if (touched.add(k)) {
                Path root = MeshTap.root();
                if (root != null) {
                    try {
                        Path f = state(root, "touched").resolve(k);
                        Files.createDirectories(f.getParent());
                        if (!Files.exists(f)) {
                            Files.createFile(f);
                        }
                    } catch (IOException e) {
                        LOG.debug("could not record that region {} was opened: {}", k, e.toString());
                    }
                }
            }
        } finally {
            GATE.unlock();
        }
    }

    private static boolean marked(Path root, String what, int rx, int rz) {
        return Files.exists(state(root, what).resolve(key(rx, rz)));
    }

    private static boolean anyFile(Path root, int rx, int rz) {
        for (String kind : KINDS) {
            if (Files.exists(root.resolve(kind).resolve("r." + rx + "." + rz + ".mca"))) {
                return true;
            }
        }
        return false;
    }

    static Region classify(Path root, int rx, int rz) {
        return TileMerge.classify(anyFile(root, rx, rz), marked(root, "ours", rx, rz), touched.contains(key(rx, rz)) || marked(root, "touched", rx, rz),
                LazyWorld.owes(OVERWORLD, rx, rz));
    }

    /**
     * Whether the host should ask for a tile of this region: nobody has opened it, and no tile was installed into it already. A
     * region that only holds the frontier of a neighbouring tile still counts, because the merge was built for exactly that.
     */
    static boolean requestable(Path root, int rx, int rz) {
        return !marked(root, "done", rx, rz) && classify(root, rx, rz) != Region.THEIRS;
    }

    /**
     * Writes a tile and its frontier. Returns installed = false, having changed nothing, when the tile's own region is
     * not ours to write to (the game got there first). Frontier chunks that fall in regions we may not write to are skipped.
     */
    static Result install(Path root, int tileRx, int tileRz, List<Chunk> chunks) throws IOException {
        GATE.lock();
        try {
            if (!TileMerge.tileCanBeInstalled(classify(root, tileRx, tileRz))) {
                return new Result(false, 0, 0, 0);
            }
            Map<String, Region> regions = new TreeMap<>();
            Map<String, List<Chunk>> byRegion = new TreeMap<>();
            for (Chunk c : chunks) {
                int rx = c.cx() >> 5;
                int rz = c.cz() >> 5;
                String k = key(rx, rz);
                regions.computeIfAbsent(k, x -> classify(root, rx, rz));
                byRegion.computeIfAbsent(k, x -> new ArrayList<>()).add(c);
            }
            int written = 0;
            int kept = 0;
            int skipped = 0;
            try (RegionIo io = new RegionIo(root)) {
                for (var e : byRegion.entrySet()) {
                    Region state = regions.get(e.getKey());
                    if (state == Region.THEIRS) {
                        skipped++;
                        continue;
                    }
                    String[] parts = e.getKey().split("\\.");
                    int rx = Integer.parseInt(parts[0]);
                    int rz = Integer.parseInt(parts[1]);
                    // recorded before the first byte is written: a region left half filled by a crash is still ours to finish
                    Path mark = state(root, "ours").resolve(e.getKey());
                    Files.createDirectories(mark.getParent());
                    if (!Files.exists(mark)) {
                        Files.createFile(mark);
                    }
                    Set<Long> wrote = new HashSet<>();
                    for (Chunk c : e.getValue()) {
                        if (!c.kind().equals("region")) {
                            continue;
                        }
                        String existing = null;
                        if (state == Region.MINE) {
                            CompoundTag old = io.read("region", c.cx(), c.cz());
                            existing = old == null ? null : old.getString("Status");
                        }
                        if (TileMerge.shouldWrite(!c.frontier(), existing, c.status())) {
                            // a chunk that is replaced takes its old entities and points of interest with it
                            io.clear("entities", c.cx(), c.cz());
                            io.clear("poi", c.cx(), c.cz());
                            io.writeBytes("region", c.cx(), c.cz(), c.nbt());
                            wrote.add(((long) c.cx() << 32) | (c.cz() & 0xFFFFFFFFL));
                            written++;
                        } else {
                            kept++;
                        }
                    }
                    // entities and points of interest belong to the terrain chunk they came with, and only travel with it
                    for (Chunk c : e.getValue()) {
                        if (!c.kind().equals("region") && wrote.contains(((long) c.cx() << 32) | (c.cz() & 0xFFFFFFFFL))) {
                            io.writeBytes(c.kind(), c.cx(), c.cz(), c.nbt());
                            written++;
                        }
                    }
                    LOG.debug("tile install: region {},{} was {}, {} chunk entries written", rx, rz, state, wrote.size());
                }
            }
            if (written > 0) {
                Path done = state(root, "done").resolve(key(tileRx, tileRz));
                Files.createDirectories(done.getParent());
                if (!Files.exists(done)) {
                    Files.createFile(done);
                }
            }
            return new Result(true, written, kept, skipped);
        } finally {
            GATE.unlock();
        }
    }
}
