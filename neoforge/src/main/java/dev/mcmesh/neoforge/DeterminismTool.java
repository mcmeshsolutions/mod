package dev.mcmesh.neoforge;

import dev.mcmesh.core.McaReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/** Research: compares the chunks of two world folders generated from the same seed, ignoring the fields that legitimately differ per run. */
public final class DeterminismTool {
    private static final Set<String> IGNORED = Set.of("LastUpdate", "InhabitedTime");

    private DeterminismTool() { }

    public static void main(String[] a) throws Exception {
        Path left = Path.of(a[0]).resolve("world/region");
        Path right = Path.of(a[1]).resolve("world/region");
        int inner = a.length > 2 ? Integer.parseInt(a[2]) : Integer.MAX_VALUE;
        // the server keeps the spawn area loaded and ticks it, so its chunks differ for reasons that are not generation
        int skipSpawn = a.length > 3 ? Integer.parseInt(a[3]) : 0;
        int same = 0;
        int different = 0;
        int onlyLeft = 0;
        int onlyRight = 0;
        int notBothFull = 0;
        Map<String, Integer> keys = new TreeMap<>();
        Map<String, Integer> status = new TreeMap<>();
        String example = null;
        List<String> diffCoords = new java.util.ArrayList<>();
        // "semantic": for every chunk that differs byte-wise, say whether the blocks and biomes themselves differ or
        // only the palette order does. Off by default so the existing numbers keep their meaning.
        boolean semantic = a.length > 4 && a[4].equals("semantic");
        Map<String, Integer> verdicts = new TreeMap<>();
        long[] blockCells = new long[2];
        long[] biomeCells = new long[2];
        String[] firstReal = new String[1];
        Map<String, Integer> swaps = new TreeMap<>();
        try (Stream<Path> files = Files.list(left)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".mca")).sorted().toList()) {
                Path g = right.resolve(f.getFileName());
                int[] r = McaReader.regionOf(f);
                for (int i = 0; i < 1024; i++) {
                    int cx = r[0] * 32 + (i & 31);
                    int cz = r[1] * 32 + (i >> 5);
                    if (Math.abs(cx) > inner || Math.abs(cz) > inner) {
                        continue;
                    }
                    if (Math.abs(cx) <= skipSpawn && Math.abs(cz) <= skipSpawn) {
                        continue;
                    }
                    CompoundTag l = read(f, cx, cz);
                    CompoundTag rt = Files.exists(g) ? read(g, cx, cz) : null;
                    if (l == null && rt == null) {
                        continue;
                    }
                    if (l == null) {
                        onlyRight++;
                        continue;
                    }
                    if (rt == null) {
                        onlyLeft++;
                        continue;
                    }
                    String ls = l.getString("Status");
                    String rs = rt.getString("Status");
                    status.merge(ls.equals(rs) ? ls : ls + " vs " + rs, 1, Integer::sum);
                    if (!ls.equals("minecraft:full") || !rs.equals("minecraft:full")) {
                        notBothFull++;
                        continue;
                    }
                    l = withoutLight(l);
                    rt = withoutLight(rt);
                    List<String> diff = new java.util.ArrayList<>();
                    for (String k : l.getAllKeys()) {
                        if (!IGNORED.contains(k) && !tagEquals(l.get(k), rt.get(k))) {
                            diff.add(k);
                        }
                    }
                    for (String k : rt.getAllKeys()) {
                        if (!l.contains(k) && !IGNORED.contains(k)) {
                            diff.add(k);
                        }
                    }
                    if (diff.isEmpty()) {
                        same++;
                    } else {
                        different++;
                        diffCoords.add(cx + "," + cz);
                        diff.forEach(k -> keys.merge(k, 1, Integer::sum));
                        if (example == null) {
                            example = "chunk " + cx + "," + cz + " differs in " + diff;
                        }
                        if (semantic) {
                            classify(l, rt, cx, cz, verdicts, blockCells, biomeCells, firstReal, swaps);
                        }
                    }
                }
            }
        }
        System.out.println("skipped " + notBothFull + " chunks that were not fully generated in both runs (border chunks, timing dependent)");
        System.out.println("compared " + (same + different) + " fully generated chunks, light ignored: " + same + " identical, " + different + " different, " + onlyLeft + " only in the first, " + onlyRight + " only in the second");
        System.out.println("status of the compared chunks: " + status);
        System.out.println("top level keys that differ (chunks): " + keys);
        if (!diffCoords.isEmpty()) {
            System.out.println("different chunks: " + diffCoords);
        }
        if (example != null) {
            System.out.println("first difference: " + example);
        }
        if (semantic) {
            System.out.println("semantic verdicts for the differing chunks: " + verdicts);
            System.out.println("block cells compared " + blockCells[0] + ", differing " + blockCells[1]
                    + "; biome cells compared " + biomeCells[0] + ", differing " + biomeCells[1]);
            if (firstReal[0] != null) {
                System.out.println("first real difference: " + firstReal[0]);
            }
            System.out.println("distinct biome substitutions: " + swaps.size());
            swaps.entrySet().stream()
                    .sorted((x, y) -> Integer.compare(y.getValue(), x.getValue()))
                    .limit(15)
                    .forEach(e -> System.out.println("  " + e.getValue() + " cells: " + e.getKey()));
        }
    }

    /**
     * Decides, for one chunk that differs byte-wise, whether the world itself differs. Counts cells so a one-block
     * difference cannot hide behind a whole-chunk verdict. `cells[0]` is cells compared, `cells[1]` cells differing.
     */
    static void classify(CompoundTag l, CompoundTag r, int cx, int cz, Map<String, Integer> verdicts,
            long[] blockCells, long[] biomeCells, String[] firstReal, Map<String, Integer> swaps) {
        var ls = l.getList("sections", 10);
        var rs = r.getList("sections", 10);
        Map<Integer, CompoundTag> byY = new TreeMap<>();
        for (int i = 0; i < rs.size(); i++) {
            byY.put((int) rs.getCompound(i).getByte("Y"), rs.getCompound(i));
        }
        boolean blocksDiffer = false;
        boolean biomesDiffer = false;
        boolean undecided = false;
        for (int i = 0; i < ls.size(); i++) {
            CompoundTag a = ls.getCompound(i);
            CompoundTag b = byY.get((int) a.getByte("Y"));
            if (b == null) {
                blocksDiffer = true;
                continue;
            }
            String[] la = resolve(a, "block_states", 4096);
            String[] lb = resolve(b, "block_states", 4096);
            if (la == null || lb == null) {
                undecided |= (la == null) != (lb == null);
            } else {
                for (int c = 0; c < 4096; c++) {
                    blockCells[0]++;
                    if (!la[c].equals(lb[c])) {
                        blockCells[1]++;
                        blocksDiffer = true;
                    }
                }
            }
            String[] ba = resolve(a, "biomes", 64);
            String[] bb = resolve(b, "biomes", 64);
            if (ba != null && bb != null) {
                for (int c = 0; c < 64; c++) {
                    biomeCells[0]++;
                    if (!ba[c].equals(bb[c])) {
                        biomeCells[1]++;
                        biomesDiffer = true;
                        // a consistent a -> b mapping means the biomes were relabelled, not regenerated
                        swaps.merge(ba[c] + " -> " + bb[c], 1, Integer::sum);
                    }
                }
            }
        }
        if (!blocksDiffer && !biomesDiffer && !undecided) {
            // the blocks are the same, so whatever differs is outside the block data: say if it is only list order
            var lb = l.getList("block_entities", 10);
            var rb = r.getList("block_entities", 10);
            List<String> ll = new java.util.ArrayList<>();
            List<String> rl = new java.util.ArrayList<>();
            for (int i = 0; i < lb.size(); i++) {
                ll.add(lb.getCompound(i).toString());
            }
            for (int i = 0; i < rb.size(); i++) {
                rl.add(rb.getCompound(i).toString());
            }
            java.util.Collections.sort(ll);
            java.util.Collections.sort(rl);
            verdicts.merge(ll.equals(rl)
                    ? "same blocks, block entities differ in list order only"
                    : "same blocks, block entity contents differ (" + lb.size() + " vs " + rb.size() + ")", 1, Integer::sum);
            if (firstReal[0] == null && !ll.equals(rl)) {
                firstReal[0] = "chunk " + cx + "," + cz + " block entity example: "
                        + (ll.isEmpty() ? "none" : ll.get(0)) + " VS " + (rl.isEmpty() ? "none" : rl.get(0));
            }
            Map<String, CompoundTag> byPos = new TreeMap<>();
            for (int i = 0; i < rb.size(); i++) {
                CompoundTag e = rb.getCompound(i);
                byPos.put(e.getInt("x") + "," + e.getInt("y") + "," + e.getInt("z"), e);
            }
            for (int i = 0; i < lb.size(); i++) {
                CompoundTag e = lb.getCompound(i);
                CompoundTag o = byPos.get(e.getInt("x") + "," + e.getInt("y") + "," + e.getInt("z"));
                if (o == null) {
                    verdicts.merge("block entity " + e.getString("id") + " only on one side", 1, Integer::sum);
                    continue;
                }
                java.util.Set<String> ks = new java.util.TreeSet<>(e.getAllKeys());
                ks.addAll(o.getAllKeys());
                List<String> d = new java.util.ArrayList<>();
                for (String k : ks) {
                    if (!java.util.Objects.equals(e.get(k), o.get(k))) {
                        d.add(k);
                    }
                }
                if (!d.isEmpty()) {
                    verdicts.merge("block entity " + e.getString("id") + " differs in " + d, 1, Integer::sum);
                    if (d.contains("SpawnData")) {
                        verdicts.merge("spawner mob " + e.getCompound("SpawnData").getCompound("entity").getString("id") + " vs " + o.getCompound("SpawnData").getCompound("entity").getString("id") + " at y=" + e.getInt("y"), 1, Integer::sum);
                    }
                }
            }
            return;
        }
        String verdict = blocksDiffer && biomesDiffer ? "blocks and biomes differ"
                : blocksDiffer ? "blocks differ, biomes identical"
                : biomesDiffer ? "biomes differ, blocks identical"
                : undecided ? "could not resolve a section"
                : "same world, serialised differently (palette order only)";
        verdicts.merge(verdict, 1, Integer::sum);
        if (firstReal[0] == null && (blocksDiffer || biomesDiffer)) {
            firstReal[0] = "chunk " + cx + "," + cz + ": " + verdict;
        }
    }

    /**
     * A section stores a palette plus indices into it, so the same blocks in a different palette order serialise
     * differently. This resolves every one of the 4096 cells to its palette entry, which is what the world actually is.
     * Null when the section has no block data. `which` is "block_states" or "biomes" (biomes are 4x4x4, 64 cells).
     */
    static String[] resolve(CompoundTag section, String which, int cells) {
        if (!section.contains(which, 10)) {
            return null;
        }
        CompoundTag container = section.getCompound(which);
        var palette = container.getList("palette", which.equals("biomes") ? 8 : 10);
        if (palette.isEmpty()) {
            return null;
        }
        String[] names = new String[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            names[i] = which.equals("biomes") ? palette.getString(i) : stateName(palette.getCompound(i));
        }
        String[] out = new String[cells];
        if (!container.contains("data", 12)) {
            // a single-entry palette stores no indices: every cell is that entry
            java.util.Arrays.fill(out, names[0]);
            return out;
        }
        long[] data = container.getLongArray("data");
        int bits = Math.max(which.equals("biomes") ? 1 : 4, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        for (int i = 0; i < cells; i++) {
            int li = i / perLong;
            if (li >= data.length) {
                return null;
            }
            int idx = (int) ((data[li] >>> ((i % perLong) * bits)) & mask);
            out[i] = idx < names.length ? names[idx] : "?" + idx;
        }
        return out;
    }

    /** Block state as one canonical string, properties sorted, so two orderings of the same state compare equal. */
    private static String stateName(CompoundTag state) {
        StringBuilder b = new StringBuilder(state.getString("Name"));
        if (state.contains("Properties", 10)) {
            CompoundTag p = state.getCompound("Properties");
            b.append('[');
            for (String k : new java.util.TreeSet<>(p.getAllKeys())) {
                b.append(k).append('=').append(p.getString(k)).append(',');
            }
            b.append(']');
        }
        return b.toString();
    }

    static CompoundTag withoutLight(CompoundTag c) {
        CompoundTag copy = c.copy();
        var sections = copy.getList("sections", 10);
        for (int i = 0; i < sections.size(); i++) {
            sections.getCompound(i).remove("BlockLight");
            sections.getCompound(i).remove("SkyLight");
        }
        copy.remove("isLightOn");
        return copy;
    }

    private static boolean tagEquals(Tag x, Tag y) {
        return x == null ? y == null : x.equals(y);
    }

    private static CompoundTag read(Path mca, int cx, int cz) throws Exception {
        byte[] raw = McaReader.readChunk(mca, cx, cz, null);
        if (raw == null) {
            return null;
        }
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(raw)), NbtAccounter.unlimitedHeap());
    }
}
