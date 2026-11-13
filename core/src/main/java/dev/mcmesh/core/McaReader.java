package dev.mcmesh.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads Anvil region files (.mca) without ever writing to them, so it is safe next to whatever is writing the world.
 * The header holds, for each of the 1024 chunks, where its data sits and when it was last written; that is enough to find
 * the chunks that changed since a point in time without touching the others.
 */
public final class McaReader {
    private static final int SECTOR = 4096;
    private static final int MAX_CHUNK = 64 << 20;

    private McaReader() { }

    public record Changed(int cx, int cz, int timestamp) { }

    /** Wraps a compressed payload for a compression type this class does not decode itself (for example LZ4). */
    public interface Decompressor {
        InputStream wrap(int type, InputStream raw) throws IOException;
    }

    /** Region coordinates from a name like r.-3.12.mca, or null. */
    public static int[] regionOf(Path mca) {
        String[] p = mca.getFileName().toString().split("\\.");
        if (p.length != 4 || !p[0].equals("r") || !p[3].equals("mca")) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Chunks stored in the file whose last-written time is at or after sinceSec. */
    public static List<Changed> changedSince(Path mca, long sinceSec) throws IOException {
        int[] r = regionOf(mca);
        if (r == null || Files.size(mca) < 2 * SECTOR) {
            return List.of();
        }
        ByteBuffer header = ByteBuffer.allocate(2 * SECTOR);
        try (FileChannel ch = FileChannel.open(mca, StandardOpenOption.READ)) {
            while (header.hasRemaining() && ch.read(header, header.position()) > 0) { }
        }
        List<Changed> out = new ArrayList<>();
        for (int i = 0; i < 1024; i++) {
            int location = header.getInt(i * 4);
            int ts = header.getInt(SECTOR + i * 4);
            if (location != 0 && ts >= sinceSec) {
                out.add(new Changed(r[0] * 32 + (i & 31), r[1] * 32 + (i >> 5), ts));
            }
        }
        return out;
    }

    /** The chunk's NBT bytes, decompressed, or null when the chunk is not in the file or cannot be read consistently. */
    public static byte[] readChunk(Path mca, int cx, int cz, Decompressor other) throws IOException {
        int index = (cx & 31) + (cz & 31) * 32;
        try (FileChannel ch = FileChannel.open(mca, StandardOpenOption.READ)) {
            ByteBuffer loc = ByteBuffer.allocate(4);
            if (ch.read(loc, index * 4L) < 4) {
                return null;
            }
            int entry = loc.getInt(0);
            int sector = entry >>> 8;
            int count = entry & 0xff;
            if (entry == 0 || sector < 2 || count == 0) {
                return null;
            }
            ByteBuffer head = ByteBuffer.allocate(5);
            if (ch.read(head, (long) sector * SECTOR) < 5) {
                return null;
            }
            int length = head.getInt(0);
            int type = head.get(4) & 0xff;
            if (length < 1 || length - 1 > count * SECTOR || length - 1 > MAX_CHUNK) {
                return null;
            }
            byte[] raw;
            if ((type & 0x80) != 0) {
                Path external = mca.resolveSibling("c." + cx + "." + cz + ".mcc");
                if (!Files.isRegularFile(external) || Files.size(external) > MAX_CHUNK) {
                    return null;
                }
                raw = Files.readAllBytes(external);
            } else {
                ByteBuffer data = ByteBuffer.allocate(length - 1);
                if (ch.read(data, (long) sector * SECTOR + 5) < data.capacity()) {
                    return null;
                }
                raw = data.array();
            }
            return decompress(type & 0x7f, raw, other);
        }
    }

    private static byte[] decompress(int type, byte[] raw, Decompressor other) throws IOException {
        InputStream in = new ByteArrayInputStream(raw);
        InputStream wrapped = switch (type) {
            case 1 -> new GZIPInputStream(in);
            case 2 -> new InflaterInputStream(in);
            case 3 -> in;
            default -> other == null ? null : other.wrap(type, in);
        };
        if (wrapped == null) {
            return null;
        }
        try (wrapped; ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, raw.length * 2))) {
            byte[] buf = new byte[1 << 14];
            for (int n; (n = wrapped.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > MAX_CHUNK) {
                    return null;
                }
            }
            return out.toByteArray();
        }
    }
}
