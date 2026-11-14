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
        int same = 0;
        int different = 0;
        int onlyLeft = 0;
        int onlyRight = 0;
        int notBothFull = 0;
        Map<String, Integer> keys = new TreeMap<>();
        Map<String, Integer> status = new TreeMap<>();
        String example = null;
        List<String> diffCoords = new java.util.ArrayList<>();
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
    }

    private static CompoundTag withoutLight(CompoundTag c) {
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
