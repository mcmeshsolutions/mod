package dev.mcmesh.neoforge;

import com.mojang.logging.LogUtils;
import dev.mcmesh.core.ChunkUploader;
import dev.mcmesh.core.WorldPaths;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.slf4j.Logger;

/** Turns an existing local world (for example a singleplayer save) into the first revision of a shared world. */
final class WorldSeeder {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int FLUSH_EVERY = 500;

    private WorldSeeder() { }

    static int seed(Path root, ChunkUploader up, String owner) throws Exception {
        movePlayerIntoPlayerdata(root, owner);
        int n = 0;
        for (Path dimDir : WorldPaths.dimDirs(root)) {
            String dim = WorldPaths.dimOf(root, dimDir);
            if (dim == null) {
                continue;
            }
            for (String kind : List.of("region", "entities", "poi")) {
                Path folder = dimDir.resolve(kind);
                if (!Files.isDirectory(folder)) {
                    continue;
                }
                List<Path> files;
                try (Stream<Path> s = Files.list(folder)) {
                    files = s.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).sorted().toList();
                }
                for (Path f : files) {
                    n += seedRegion(root, dim, kind, folder, f, up);
                }
            }
        }
        LOG.info("seeded {} chunk entries from {}", n, root);
        return n;
    }

    private static int seedRegion(Path root, String dim, String kind, Path folder, Path file, ChunkUploader up) throws Exception {
        String[] parts = file.getFileName().toString().split("\\.");
        int rx = Integer.parseInt(parts[1]);
        int rz = Integer.parseInt(parts[2]);
        ResourceKey<net.minecraft.world.level.Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dim));
        RegionStorageInfo info = new RegionStorageInfo(root.getFileName().toString(), key, kind);
        int n = 0;
        try (RegionFile rf = new RegionFile(info, file, folder, false)) {
            for (int dz = 0; dz < 32; dz++) {
                for (int dx = 0; dx < 32; dx++) {
                    ChunkPos pos = new ChunkPos((rx << 5) + dx, (rz << 5) + dz);
                    if (!rf.hasChunk(pos)) {
                        continue;
                    }
                    try (DataInputStream in = rf.getChunkDataInputStream(pos)) {
                        if (in == null) {
                            continue;
                        }
                        up.put(dim, kind, pos.x, pos.z, in.readAllBytes());
                    }
                    if (++n % FLUSH_EVERY == 0) {
                        up.flush(false);
                    }
                }
            }
        }
        up.flush(false);
        return n;
    }

    /**
     * A singleplayer save keeps its owner in level.dat. The dedicated server looks players up by the offline UUID of their name,
     * so the owner's data moves to playerdata/ under that UUID unless a file already exists there.
     */
    private static void movePlayerIntoPlayerdata(Path root, String owner) throws IOException {
        Path lvl = root.resolve("level.dat");
        if (!Files.isRegularFile(lvl) || owner == null || owner.isEmpty()) {
            return;
        }
        CompoundTag t = NbtIo.readCompressed(new ByteArrayInputStream(Files.readAllBytes(lvl)), NbtAccounter.unlimitedHeap());
        if (!t.contains("Data", 10) || !t.getCompound("Data").contains("Player", 10)) {
            return;
        }
        CompoundTag player = t.getCompound("Data").getCompound("Player");
        UUID id = UUIDUtil.createOfflinePlayerUUID(owner);
        Path dest = root.resolve("playerdata").resolve(id + ".dat");
        if (!Files.exists(dest)) {
            Files.createDirectories(dest.getParent());
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            NbtIo.writeCompressed(player, o);
            Files.write(dest, o.toByteArray());
            LOG.info("moved the singleplayer owner into playerdata for {} ({})", owner, id);
        }
    }
}
