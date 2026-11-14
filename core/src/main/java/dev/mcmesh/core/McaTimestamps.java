package dev.mcmesh.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * Sets the chunk timestamps of a region file, and its modification time, back to a past moment. A chunk written by a download is not a
 * change the player made, and the region scan finds changes by timestamp, so downloaded chunks must not look new.
 */
public final class McaTimestamps {
    private McaTimestamps() { }

    /** Changes only chunks that exist (a non-zero location entry). Missing or too small files are left alone. */
    public static void backdate(Path mca, int epochSeconds) throws IOException {
        if (!Files.isRegularFile(mca) || Files.size(mca) < 8192) {
            return;
        }
        try (RandomAccessFile f = new RandomAccessFile(mca.toFile(), "rw")) {
            byte[] header = new byte[8192];
            f.readFully(header);
            for (int i = 0; i < 1024; i++) {
                boolean present = header[i * 4] != 0 || header[i * 4 + 1] != 0 || header[i * 4 + 2] != 0 || header[i * 4 + 3] != 0;
                if (present) {
                    int o = 4096 + i * 4;
                    header[o] = (byte) (epochSeconds >>> 24);
                    header[o + 1] = (byte) (epochSeconds >>> 16);
                    header[o + 2] = (byte) (epochSeconds >>> 8);
                    header[o + 3] = (byte) epochSeconds;
                }
            }
            f.seek(4096);
            f.write(header, 4096, 4096);
        }
        Files.setLastModifiedTime(mca, FileTime.fromMillis(epochSeconds * 1000L));
    }
}
