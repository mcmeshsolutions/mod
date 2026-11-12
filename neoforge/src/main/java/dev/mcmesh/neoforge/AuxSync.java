package dev.mcmesh.neoforge;

import dev.mcmesh.core.Bytes;
import dev.mcmesh.core.ChunkUploader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Uploads every non-chunk file of the world folder when it changes. */
final class AuxSync {
    private final Map<String, String> seen = new HashMap<>();
    private final Map<String, String> stamp = new HashMap<>();

    void scan(Path root, ChunkUploader up) throws IOException {
        Set<String> present = new HashSet<>();
        for (Path f : candidates(root)) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            present.add(rel);
            String meta;
            try {
                meta = Files.size(f) + ":" + Files.getLastModifiedTime(f).toMillis();
            } catch (IOException e) {
                continue;
            }
            if (meta.equals(stamp.get(rel))) {
                continue;
            }
            stamp.put(rel, meta);
            byte[] raw = Files.readAllBytes(f);
            String h = Bytes.hex(Bytes.sha256(raw));
            if (h.equals(seen.get(rel))) {
                continue;
            }
            up.auxPut(rel, rel.equals("level.dat") ? scrubLevel(raw) : raw);
            seen.put(rel, h);
        }
        for (String gone : new ArrayList<>(seen.keySet())) {
            if (!present.contains(gone)) {
                up.auxDelete(gone);
                seen.remove(gone);
                stamp.remove(gone);
            }
        }
    }

    private static final long MAX_FILE = 128L << 20;

    /** Everything in the world folder except chunk stores and caches, so mod data (quests, claims, ships) travels with the world. */
    static List<Path> candidates(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes at) {
                String n = d.getFileName() == null ? "" : d.getFileName().toString();
                boolean skip = !d.equals(root) && (n.equals("logs") || n.equals("crash-reports") || n.startsWith(".")
                        || n.equals("region") || n.equals("entities") || n.equals("poi"));
                return skip ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes at) {
                String n = f.getFileName().toString();
                boolean skip = !at.isRegularFile() || at.size() > MAX_FILE || n.equals("session.lock") || n.equals(WorldSync.MARKER)
                        || n.equals("level.dat_old") || n.endsWith(".mca") || n.endsWith(".tmp") || n.endsWith(".mesh-tmp") || n.endsWith(".lock")
                        || n.contains(".sqlite") || n.endsWith(".log");
                if (!skip) {
                    out.add(f);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path f, IOException e) {
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    private static byte[] scrubLevel(byte[] raw) throws IOException {
        CompoundTag t = NbtIo.readCompressed(new ByteArrayInputStream(raw), NbtAccounter.unlimitedHeap());
        if (!t.contains("Data", 10) || !t.getCompound("Data").contains("Player")) {
            return raw;
        }
        t.getCompound("Data").remove("Player");
        ByteArrayOutputStream o = new ByteArrayOutputStream(raw.length);
        NbtIo.writeCompressed(t, o);
        return o.toByteArray();
    }
}
