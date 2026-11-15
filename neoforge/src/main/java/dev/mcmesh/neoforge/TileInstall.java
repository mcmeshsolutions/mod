package dev.mcmesh.neoforge;

import dev.mcmesh.core.TileMerge;
import dev.mcmesh.core.TileMerge.Region;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts a worker's chunks into the host world without ever making it worse (rules in {@link TileMerge}, reasons in
 * board/RESEARCH.md 1e). The game creates a region file the first time anything reads or writes it, and every access
 * passes {@link #touch} first, which records the region as opened before the game is let in. So a region that was never
 * touched and is either absent or was created by an earlier install is safe to write to, and anything else is left alone.
 *
 * A tile is first staged on disk, one chunk at a time and outside the lock, so neither memory nor the game waits on the
 * download. Only the commit holds the lock: for a region that does not exist yet it moves the staged files in, which takes
 * milliseconds, and only a region that already holds the frontier of an earlier tile is merged chunk by chunk.
 */
final class TileInstall {
    private static final Logger LOG = LoggerFactory.getLogger("mcmesh");
    private static final String OVERWORLD = "minecraft:overworld";
    private static final List<String> KINDS = List.of("region", "entities", "poi");
    private static final ReentrantLock GATE = new ReentrantLock();
    private static final Set<String> touched = ConcurrentHashMap.newKeySet();

    private TileInstall() { }

    /** What the commit needs to know about a staged chunk entry; the bytes are in the staged region files. */
    record Entry(String kind, int cx, int cz, String status, boolean frontier) { }

    record Result(boolean installed, int written, int keptExisting, int regionsSkipped, long lockMillis) { }

    /** A tile being downloaded: its chunks go to region files of their own, never into the world. */
    static final class Stage implements AutoCloseable {
        private final Path dir;
        private final RegionIo io;
        private final List<Entry> entries = new ArrayList<>();

        Stage(Path dir) throws IOException {
            this.dir = dir;
            deleteTree(dir);
            Files.createDirectories(dir);
            this.io = new RegionIo(dir);
        }

        /** Safe to call from several download threads. */
        void add(String kind, int cx, int cz, byte[] nbt, String status, boolean frontier) throws IOException {
            io.writeBytes(kind, cx, cz, nbt);
            synchronized (entries) {
                entries.add(new Entry(kind, cx, cz, status, frontier));
            }
        }

        List<Entry> entries() {
            synchronized (entries) {
                return List.copyOf(entries);
            }
        }

        Path dir() {
            return dir;
        }

        /** Flushes and closes the staged files; call before {@link #commit}. */
        @Override
        public void close() throws IOException {
            io.close();
        }
    }

    private static String key(int rx, int rz) {
        return rx + "." + rz;
    }

    private static Path state(Path root, String what) {
        return root.resolve("mcmesh").resolve("tiles").resolve(what);
    }

    static Path stageDir(Path root, long tile) {
        return state(root, "stage").resolve("t" + tile);
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
                        mark(state(root, "touched").resolve(k));
                    } catch (IOException e) {
                        LOG.debug("could not record that region {} was opened: {}", k, e.toString());
                    }
                }
            }
        } finally {
            GATE.unlock();
        }
    }

    private static void mark(Path f) throws IOException {
        Files.createDirectories(f.getParent());
        if (!Files.exists(f)) {
            Files.createFile(f);
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
     * Brings a staged tile into the world. Returns installed = false, having changed nothing, when the tile's own region is not
     * ours to write to (the game got there first). Frontier chunks that fall in regions we may not write to are skipped.
     */
    static Result commit(Path root, Stage stage, int tileRx, int tileRz) throws IOException {
        long t0 = System.nanoTime();
        GATE.lock();
        try {
            if (!TileMerge.tileCanBeInstalled(classify(root, tileRx, tileRz))) {
                return new Result(false, 0, 0, 0, 0);
            }
            Map<String, List<Entry>> byRegion = new TreeMap<>();
            for (Entry e : stage.entries()) {
                byRegion.computeIfAbsent(key(e.cx() >> 5, e.cz() >> 5), x -> new ArrayList<>()).add(e);
            }
            int written = 0;
            int kept = 0;
            int skipped = 0;
            try (RegionIo staged = new RegionIo(stage.dir()); RegionIo world = new RegionIo(root)) {
                for (var r : byRegion.entrySet()) {
                    String[] parts = r.getKey().split("\\.");
                    int rx = Integer.parseInt(parts[0]);
                    int rz = Integer.parseInt(parts[1]);
                    Region state = classify(root, rx, rz);
                    if (state == Region.THEIRS) {
                        skipped++;
                        continue;
                    }
                    // recorded before the first byte is written: a region left half filled by a crash is still ours to finish
                    mark(state(root, "ours").resolve(r.getKey()));
                    if (state == Region.ABSENT) {
                        written += moveIn(root, stage.dir(), rx, rz, r.getValue().size());
                    } else {
                        int[] counts = merge(staged, world, r.getValue());
                        written += counts[0];
                        kept += counts[1];
                    }
                }
            }
            if (written > 0) {
                mark(state(root, "done").resolve(key(tileRx, tileRz)));
            }
            return new Result(true, written, kept, skipped, (System.nanoTime() - t0) / 1_000_000);
        } finally {
            GATE.unlock();
        }
    }

    /** A region nobody has: the staged files become the region's files. Returns how many entries that is. */
    private static int moveIn(Path root, Path stageDir, int rx, int rz, int entries) throws IOException {
        List<Path> moved = new ArrayList<>();
        try {
            for (String kind : KINDS) {
                Path from = stageDir.resolve(kind).resolve("r." + rx + "." + rz + ".mca");
                if (!Files.exists(from)) {
                    continue;
                }
                Path to = root.resolve(kind).resolve("r." + rx + "." + rz + ".mca");
                Files.createDirectories(to.getParent());
                try {
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(from, to);
                }
                moved.add(to);
            }
        } catch (IOException e) {
            for (Path p : moved) {
                Files.deleteIfExists(p);
            }
            throw e;
        }
        return entries;
    }

    /** A region that already holds an earlier tile's frontier: chunk by chunk, by the rules in {@link TileMerge#shouldWrite}. */
    private static int[] merge(RegionIo staged, RegionIo world, List<Entry> entries) throws IOException {
        int written = 0;
        int kept = 0;
        Set<Long> wrote = new HashSet<>();
        for (Entry e : entries) {
            if (!e.kind().equals("region")) {
                continue;
            }
            CompoundTag old = world.read("region", e.cx(), e.cz());
            String existing = old == null ? null : old.getString("Status");
            if (TileMerge.shouldWrite(!e.frontier(), existing, e.status())) {
                // a chunk that is replaced takes its old entities and points of interest with it
                world.clear("entities", e.cx(), e.cz());
                world.clear("poi", e.cx(), e.cz());
                world.writeBytes("region", e.cx(), e.cz(), staged.readBytes("region", e.cx(), e.cz()));
                wrote.add(((long) e.cx() << 32) | (e.cz() & 0xFFFFFFFFL));
                written++;
            } else {
                kept++;
            }
        }
        // entities and points of interest belong to the terrain chunk they came with, and only travel with it
        for (Entry e : entries) {
            if (!e.kind().equals("region") && wrote.contains(((long) e.cx() << 32) | (e.cz() & 0xFFFFFFFFL))) {
                world.writeBytes(e.kind(), e.cx(), e.cz(), staged.readBytes(e.kind(), e.cx(), e.cz()));
                written++;
            }
        }
        return new int[] {written, kept};
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path f : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(f);
            }
        }
    }
}
