package dev.mcmesh.neoforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * Runs TileInstall against real region files in a temporary world: the cases where it must write, and above all the
 * ones where it must not. `gradle :neoforge:tile -PtoolArgs=selftest`, exit code 1 on any failure. Offline and quick,
 * because this is the code that must never lose a chunk.
 */
final class TileSelfTest {
    private static int fails;

    private TileSelfTest() { }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            fails++;
        }
    }

    private static TileInstall.Chunk region(int cx, int cz, String status, String src, boolean frontier) throws IOException {
        CompoundTag t = new CompoundTag();
        t.putInt("DataVersion", 3955);
        t.putInt("xPos", cx);
        t.putInt("zPos", cz);
        t.putString("Status", "minecraft:" + status);
        t.putString("src", src);
        return new TileInstall.Chunk("region", cx, cz, RegionIo.bytes(t), "minecraft:" + status, frontier);
    }

    private static TileInstall.Chunk entities(int cx, int cz, String src) throws IOException {
        CompoundTag t = new CompoundTag();
        t.putInt("DataVersion", 3955);
        t.putIntArray("Position", new int[] {cx, cz});
        t.put("Entities", new ListTag());
        t.putString("src", src);
        return new TileInstall.Chunk("entities", cx, cz, RegionIo.bytes(t), null, false);
    }

    private static String src(Path root, String kind, int cx, int cz) throws IOException {
        try (RegionIo io = new RegionIo(root)) {
            CompoundTag t = io.read(kind, cx, cz);
            return t == null ? null : t.getString("src");
        }
    }

    static int run() throws IOException {
        Path root = Files.createTempDirectory("tile-selftest");
        MeshTap.setRoot(root);

        // 1. nothing is there: a tile in region 0,0 and one frontier chunk that lands in the empty neighbour 1,0
        var one = new TileInstall.Result(false, 0, 0, 0);
        one = TileInstall.install(root, 0, 0, List.of(region(5, 5, "full", "T1", false), entities(5, 5, "T1"), region(33, 5, "carvers", "T1", true)));
        check("a tile goes into a region nobody has touched", one.installed() && one.written() == 3);
        check("the frontier created the neighbouring region", Files.exists(root.resolve("region/r.1.0.mca")) && "T1".equals(src(root, "region", 33, 5)));
        check("both regions are recorded as ours, and the tile as done", Files.exists(root.resolve("mcmesh/tiles/ours/0.0")) && Files.exists(root.resolve("mcmesh/tiles/ours/1.0"))
                && Files.exists(root.resolve("mcmesh/tiles/done/0.0")) && !Files.exists(root.resolve("mcmesh/tiles/done/1.0")));
        check("a region holding only a neighbour's frontier can still be asked for", TileInstall.requestable(root, 1, 0) && !TileInstall.requestable(root, 0, 0));

        // 2. the next tile lands in the region that holds the frontier of the first, and its own halo overlaps the first tile
        List<TileInstall.Chunk> second = new ArrayList<>();
        second.add(region(33, 5, "full", "T2", false));
        second.add(region(5, 5, "full", "T2", true));
        second.add(region(34, 6, "biomes", "T2", true));
        var two = TileInstall.install(root, 1, 0, second);
        check("a second tile goes into the region that held a frontier", two.installed());
        check("the verified tile chunk replaced the frontier chunk it found", "T2".equals(src(root, "region", 33, 5)));
        check("an unchecked halo chunk did not replace a finished chunk of the same status", "T1".equals(src(root, "region", 5, 5)));
        check("a frontier chunk went into empty space", "T2".equals(src(root, "region", 34, 6)));

        // 3. replacing a chunk takes its old entities with it
        TileInstall.install(root, 2, 0, List.of(region(70, 3, "full", "T3", false), entities(70, 3, "T3")));
        check("the entities came with their chunk", "T3".equals(src(root, "entities", 70, 3)));
        TileInstall.install(root, 2, 0, List.of(region(70, 3, "full", "T3b", false)));
        check("a replaced chunk loses its old entities", src(root, "entities", 70, 3) == null && "T3b".equals(src(root, "region", 70, 3)));

        // 4. a region that was there before we were: never touched
        Files.createDirectories(root.resolve("region"));
        Path theirs = root.resolve("region/r.5.5.mca");
        Files.write(theirs, new byte[8192]);
        long size = Files.size(theirs);
        var refused = TileInstall.install(root, 5, 5, List.of(region(170, 170, "full", "X", false)));
        check("a tile is refused when its region already exists and is not ours", !refused.installed() && Files.size(theirs) == size && !Files.exists(root.resolve("mcmesh/tiles/ours/5.5")));
        var skipped = TileInstall.install(root, 4, 5, List.of(region(130, 170, "full", "T4", false), region(165, 170, "carvers", "T4", true)));
        check("frontier chunks that land in someone else's region are skipped, the tile is not", skipped.installed() && skipped.regionsSkipped() == 1 && Files.size(theirs) == size);

        // 5. the game opened it
        TileInstall.touch(7, 7);
        var opened = TileInstall.install(root, 7, 7, List.of(region(230, 230, "full", "Y", false)));
        check("a tile is refused once the game has opened its region", !opened.installed() && !Files.exists(root.resolve("region/r.7.7.mca")));
        check("the opened region is recorded on disk, so a restart remembers", Files.exists(root.resolve("mcmesh/tiles/touched/7.7")));
        check("and it is no longer worth asking for", !TileInstall.requestable(root, 7, 7));
        var frontierOnly = TileInstall.install(root, 8, 7, List.of(region(260, 230, "full", "T5", false), region(231, 230, "carvers", "T5", true)));
        check("frontier chunks are kept out of a region the game opened", frontierOnly.installed() && frontierOnly.regionsSkipped() == 1 && !Files.exists(root.resolve("region/r.7.7.mca")));

        System.out.println(fails == 0 ? "all install checks passed" : fails + " install checks failed");
        return fails;
    }
}
