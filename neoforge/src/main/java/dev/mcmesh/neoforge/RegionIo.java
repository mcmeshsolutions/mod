package dev.mcmesh.neoforge;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * The overworld's chunks of one world folder (`region`, `entities`, `poi`), read and written through the game's own
 * RegionFile so the file format is never reimplemented. For a world nothing else has open, or after a flush.
 */
final class RegionIo implements AutoCloseable {
    private final Path root;
    private final Map<String, RegionFile> open = new HashMap<>();

    RegionIo(Path worldRoot) {
        this.root = worldRoot;
    }

    private RegionFile file(String kind, int cx, int cz, boolean create) throws IOException {
        Path dir = root.resolve(kind);
        Path f = dir.resolve("r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
        RegionFile rf = open.get(f.toString());
        if (rf == null) {
            if (!create && !Files.exists(f)) {
                return null;
            }
            Files.createDirectories(dir);
            rf = new RegionFile(new RegionStorageInfo("tile", Level.OVERWORLD, kind), f, dir, false);
            open.put(f.toString(), rf);
        }
        return rf;
    }

    /** The chunk, or null when this world never stored one. */
    CompoundTag read(String kind, int cx, int cz) throws IOException {
        RegionFile rf = file(kind, cx, cz, false);
        ChunkPos p = new ChunkPos(cx, cz);
        if (rf == null || !rf.hasChunk(p)) {
            return null;
        }
        try (DataInputStream in = rf.getChunkDataInputStream(p)) {
            return in == null ? null : NbtIo.read(in, NbtAccounter.unlimitedHeap());
        }
    }

    /** The uncompressed NBT bytes of a stored chunk, or null: what the gateway holds and writeBytes takes. */
    byte[] readBytes(String kind, int cx, int cz) throws IOException {
        RegionFile rf = file(kind, cx, cz, false);
        ChunkPos p = new ChunkPos(cx, cz);
        if (rf == null || !rf.hasChunk(p)) {
            return null;
        }
        try (DataInputStream in = rf.getChunkDataInputStream(p)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    void write(String kind, int cx, int cz, CompoundTag tag) throws IOException {
        try (DataOutputStream out = file(kind, cx, cz, true).getChunkDataOutputStream(new ChunkPos(cx, cz))) {
            NbtIo.write(tag, out);
        }
    }

    /** Removes a chunk entry if there is one. */
    void clear(String kind, int cx, int cz) throws IOException {
        RegionFile rf = file(kind, cx, cz, false);
        ChunkPos p = new ChunkPos(cx, cz);
        if (rf != null && rf.hasChunk(p)) {
            rf.clear(p);
        }
    }

    /** Stores a chunk from the uncompressed NBT bytes the gateway holds. */
    synchronized void writeBytes(String kind, int cx, int cz, byte[] nbt) throws IOException {
        try (DataOutputStream out = file(kind, cx, cz, true).getChunkDataOutputStream(new ChunkPos(cx, cz))) {
            out.write(nbt);
        }
    }

    /** The bytes the gateway stores for a chunk: its NBT, uncompressed, exactly as MeshTap and the region scan produce them. */
    static byte[] bytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            NbtIo.write(tag, out);
        }
        return bos.toByteArray();
    }

    @Override
    public void close() throws IOException {
        for (RegionFile rf : open.values()) {
            rf.close();
        }
    }
}
