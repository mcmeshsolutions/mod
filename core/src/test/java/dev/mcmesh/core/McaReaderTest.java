package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McaReaderTest {
    private static byte[] zlib(byte[] d) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(o)) {
            z.write(d);
        }
        return o.toByteArray();
    }

    private static byte[] gzip(byte[] d) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(o)) {
            z.write(d);
        }
        return o.toByteArray();
    }

    /** One chunk per entry: {localX, localZ, timestamp, compressionType, payload}. Written one chunk per two sectors. */
    private static Path region(Path dir, String name, Object[][] chunks) throws Exception {
        ByteBuffer file = ByteBuffer.allocate((2 + chunks.length * 2) * 4096);
        int sector = 2;
        for (Object[] c : chunks) {
            int idx = (int) c[0] + (int) c[1] * 32;
            byte[] payload = (byte[]) c[4];
            file.putInt(idx * 4, (sector << 8) | 2);
            file.putInt(4096 + idx * 4, (int) c[2]);
            file.putInt(sector * 4096, payload.length + 1);
            file.put(sector * 4096 + 4, (byte) (int) c[3]);
            file.put(sector * 4096 + 5, payload, 0, payload.length);
            sector += 2;
        }
        Path p = dir.resolve(name);
        Files.write(p, file.array());
        return p;
    }

    @Test
    void regionCoordinatesComeFromTheFileName() {
        assertArrayEquals(new int[] {-3, 12}, McaReader.regionOf(Path.of("region/r.-3.12.mca")));
        assertNull(McaReader.regionOf(Path.of("region/r.a.1.mca")));
        assertNull(McaReader.regionOf(Path.of("region/level.dat")));
    }

    @Test
    void onlyChunksWrittenSinceTheGivenTimeAreListedWithAbsoluteCoordinates(@TempDir Path dir) throws Exception {
        Path f = region(dir, "r.-1.2.mca", new Object[][] {
            {0, 0, 1000, 2, zlib(new byte[] {1})},
            {31, 1, 2000, 2, zlib(new byte[] {2})},
            {5, 31, 3000, 2, zlib(new byte[] {3})},
        });
        List<McaReader.Changed> c = McaReader.changedSince(f, 2000);
        assertEquals(2, c.size());
        assertEquals(new McaReader.Changed(-1 * 32 + 31, 2 * 32 + 1, 2000), c.get(0));
        assertEquals(new McaReader.Changed(-1 * 32 + 5, 2 * 32 + 31, 3000), c.get(1));
        assertEquals(3, McaReader.changedSince(f, 0).size());
        assertTrue(McaReader.changedSince(f, 9999).isEmpty());
    }

    @Test
    void zlibGzipAndUncompressedChunksAreReadBackIdentically(@TempDir Path dir) throws Exception {
        byte[] nbt = "pretend this is chunk nbt".repeat(50).getBytes();
        Path f = region(dir, "r.0.0.mca", new Object[][] {
            {1, 1, 10, 2, zlib(nbt)}, {2, 1, 10, 1, gzip(nbt)}, {3, 1, 10, 3, nbt},
        });
        for (int x = 1; x <= 3; x++) {
            assertArrayEquals(nbt, McaReader.readChunk(f, x, 1, null), "type at x=" + x);
        }
    }

    @Test
    void unknownCompressionNeedsADecompressorAndGetsTheRawStream(@TempDir Path dir) throws Exception {
        Path f = region(dir, "r.0.0.mca", new Object[][] {{4, 4, 10, 4, new byte[] {9, 8, 7}}});
        assertNull(McaReader.readChunk(f, 4, 4, null), "no decoder for type 4, skip rather than guess");
        byte[] got = McaReader.readChunk(f, 4, 4, (type, raw) -> {
            assertEquals(4, type);
            return raw;
        });
        assertArrayEquals(new byte[] {9, 8, 7}, got);
    }

    @Test
    void anExternalOversizeChunkIsReadFromItsMccFile(@TempDir Path dir) throws Exception {
        byte[] nbt = "big".repeat(1000).getBytes();
        Path f = region(dir, "r.0.0.mca", new Object[][] {{2, 3, 10, 2 | 0x80, new byte[0]}});
        Files.write(dir.resolve("c.2.3.mcc"), zlib(nbt));
        assertArrayEquals(nbt, McaReader.readChunk(f, 2, 3, null));
    }

    @Test
    void missingTornOrEmptyChunksReturnNullInsteadOfThrowing(@TempDir Path dir) throws Exception {
        Path f = region(dir, "r.0.0.mca", new Object[][] {{0, 0, 10, 2, zlib(new byte[] {1})}});
        assertNull(McaReader.readChunk(f, 7, 7, null), "not stored");
        byte[] bytes = Files.readAllBytes(f);
        ByteBuffer.wrap(bytes).putInt(2 * 4096, 999_999_999);
        Path torn = dir.resolve("r.1.0.mca");
        Files.write(torn, bytes);
        assertNull(McaReader.readChunk(torn, 0, 0, null), "an implausible length is a torn write");
        Path tiny = dir.resolve("r.2.0.mca");
        Files.write(tiny, new byte[100]);
        assertTrue(McaReader.changedSince(tiny, 0).isEmpty());
    }
}
